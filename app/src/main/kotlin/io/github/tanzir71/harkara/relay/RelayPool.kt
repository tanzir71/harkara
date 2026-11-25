package io.github.tanzir71.harkara.relay

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.relay.Backoff
import io.github.tanzir71.harkara.core.relay.ClientMessage
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.relay.NegMessages
import io.github.tanzir71.harkara.core.relay.Negentropy
import io.github.tanzir71.harkara.core.relay.RelayInfo
import io.github.tanzir71.harkara.core.relay.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED }

/** An event a relay sent us, with where it came from. */
data class IncomingEvent(val event: Event, val relayUrl: String, val subscriptionId: String)

/** A relay's `OK` for one of our events. */
data class RelayAck(val eventId: String, val relayUrl: String, val accepted: Boolean, val message: String)

/**
 * OkHttp WebSocket pool over the user's relay list (NIP-01). Keeps subscriptions alive across
 * reconnects (resuming from the last EOSE with `since`), respects NIP-11 limits, reconnects with
 * jittered exponential backoff, and reports `OK`s so the outbox can mark delivery. It only runs while
 * something started it: the app in the foreground or a WorkManager sync.
 */
class RelayPool(
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val subscriptions = ConcurrentHashMap<String, List<Filter>>()
    private val resumeSlack = ConcurrentHashMap<String, Long>()
    private val mutableStatus = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    private val mutableInfo = MutableStateFlow<Map<String, RelayInfo>>(emptyMap())
    private val mutableIncoming = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 1024)
    private val mutableAcks = MutableSharedFlow<RelayAck>(extraBufferCapacity = 256)
    private val mutableEose = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    val status: StateFlow<Map<String, RelayStatus>> = mutableStatus.asStateFlow()
    val info: StateFlow<Map<String, RelayInfo>> = mutableInfo.asStateFlow()
    val incoming: SharedFlow<IncomingEvent> = mutableIncoming.asSharedFlow()
    val acks: SharedFlow<RelayAck> = mutableAcks.asSharedFlow()
    /** (relayUrl, subscriptionId) when a relay finishes sending stored events. */
    val eose: SharedFlow<Pair<String, String>> = mutableEose.asSharedFlow()

    val connectedCount: Int get() = mutableStatus.value.count { it.value == RelayStatus.CONNECTED }

    /** Connects to [urls], dropping relays no longer listed. */
    fun setRelays(urls: List<String>) {
        val wanted = urls.map(::normalize).toSet()
        connections.keys.filterNot { it in wanted }.forEach { url -> connections.remove(url)?.close() }
        wanted.filterNot { connections.containsKey(it) }.forEach { url ->
            connections[url] = Connection(url).also { it.start() }
        }
    }

    fun stop() {
        connections.values.forEach { it.close() }
        connections.clear()
        mutableStatus.value = emptyMap()
    }

    /**
     * Opens (or replaces) a subscription on every relay; it survives reconnects. On a reconnect it resumes
     * from the last EOSE minus [resumeSlackSeconds] (gift wraps are back-dated up to two days, NIP-59).
     */
    fun subscribe(id: String, filters: List<Filter>, resumeSlackSeconds: Long = 0) {
        subscriptions[id] = filters
        resumeSlack[id] = resumeSlackSeconds
        connections.values.forEach { it.sendReq(id, filters, resume = false) }
    }

    fun unsubscribe(id: String) {
        subscriptions.remove(id)
        connections.values.forEach { it.send(ClientMessage.Close(id).toJson()) }
    }

    /**
     * NIP-77 catch-up: on relays that advertise negentropy, reconciles [local] (what we already hold for
     * [filter]) and fetches only the missing events, which arrive on [subscriptionId]'s incoming stream.
     * Relays without NIP-77 rely on the normal subscription. Returns how many relays started a sync.
     */
    fun reconcile(subscriptionId: String, filter: Filter, local: List<Negentropy.Item>): Int = connections.values.count { c ->
        c.isConnected && (mutableInfo.value[c.url]?.supportsNegentropy == true) && c.startNegentropy(subscriptionId, filter, local)
    }

    /** Ids fetched through negentropy so far (for tests and stats). */
    val negentropyFetched = MutableStateFlow(0)

    /** Sends [event] to every connected relay that accepts its size. Returns how many it left on. */
    fun publish(event: Event, only: Collection<String>? = null): Int {
        val json = event.toJson()
        val bytes = json.encodeToByteArray().size
        val targets = only?.map(::normalize)?.toSet()
        return connections.values.count { c ->
            (targets == null || c.url in targets) && c.isConnected && (mutableInfo.value[c.url] ?: RelayInfo.Unknown).accepts(bytes) && c.send(ClientMessage.Publish(event).toJson())
        }
    }

    private inner class Connection(val url: String) {
        @Volatile private var socket: WebSocket? = null
        @Volatile var isConnected = false
            private set
        @Volatile private var closed = false
        private val backoff = Backoff()
        private val lastEose = ConcurrentHashMap<String, Long>()
        private val negSessions = ConcurrentHashMap<String, Pair<String, Negentropy>>()
        private val fetchSubs = ConcurrentHashMap<String, String>()

        fun startNegentropy(subscriptionId: String, filter: Filter, local: List<Negentropy.Item>): Boolean {
            val negId = "neg-" + subscriptionId
            val maxLen = mutableInfo.value[url]?.maxMessageLength ?: 0
            val session = Negentropy(local, frameSizeLimit = if (maxLen in 4096..500_000) maxLen / 2 else 0)
            negSessions[negId] = subscriptionId to session
            return send(NegMessages.open(negId, filter, session.initiate()))
        }

        private fun onNegMessage(negId: String, message: String) {
            val (subId, session) = negSessions[negId] ?: return
            val reply = runCatching { session.reconcile(message) }.getOrElse {
                negSessions.remove(negId)
                send(NegMessages.close(negId))
                return
            }
            if (reply != null) {
                send(NegMessages.msg(negId, reply))
                return
            }
            negSessions.remove(negId)
            send(NegMessages.close(negId))
            val need = session.needIds.toList()
            need.chunked(100).forEachIndexed { i, ids ->
                val fetchId = "~"
                fetchSubs[fetchId] = subId
                send(ClientMessage.Req(fetchId, listOf(Filter(ids = ids))).toJson())
            }
            negentropyFetched.update { it + need.size }
        }
        private var reconnectJob: Job? = null

        fun start() {
            setStatus(RelayStatus.CONNECTING)
            scope.launch(Dispatchers.IO) {
                fetchInfo()
                open()
            }
        }

        private suspend fun fetchInfo() {
            val http = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(http).header("Accept", "application/nostr+json").build()).execute().use { r ->
                        if (r.isSuccessful) RelayInfo.parse(r.body?.string().orEmpty()) else RelayInfo.Unknown
                    }
                }.getOrDefault(RelayInfo.Unknown)
            }
            mutableInfo.update { it + (url to parsed) }
        }

        private fun open() {
            if (closed) return
            socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
        }

        fun send(text: String): Boolean = socket?.takeIf { isConnected }?.send(text) ?: false

        fun sendReq(id: String, filters: List<Filter>, resume: Boolean) {
            val since = if (resume) lastEose[id]?.let { it - (resumeSlack[id] ?: 0) } else null
            val adjusted = filters.map { f -> if (since != null) f.copy(since = maxOf(f.since ?: 0, since)) else f }
            val maxFilters = mutableInfo.value[url]?.maxFilters
            send(ClientMessage.Req(id, if (maxFilters != null) adjusted.take(maxFilters) else adjusted).toJson())
        }

        fun close() {
            closed = true
            setStatus(RelayStatus.DISCONNECTED)
            reconnectJob?.cancel()
            isConnected = false
            socket?.close(1000, "bye")
            socket = null
        }

        private fun scheduleReconnect() {
            if (closed) return
            setStatus(RelayStatus.DISCONNECTED)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(backoff.nextDelayMs())
                if (!closed) {
                    setStatus(RelayStatus.CONNECTING)
                    open()
                }
            }
        }

        private fun setStatus(s: RelayStatus) {
            if (closed && s != RelayStatus.DISCONNECTED) return
            mutableStatus.update { if (closed) it - url else it + (url to s) }
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                backoff.reset()
                setStatus(RelayStatus.CONNECTED)
                subscriptions.forEach { (id, filters) -> sendReq(id, filters, resume = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val msg = RelayMessage.parse(text)) {
                    is RelayMessage.EventMessage -> {
                        val sub = fetchSubs[msg.subscriptionId] ?: msg.subscriptionId.takeIf { subscriptions.containsKey(it) }
                        if (sub != null) mutableIncoming.tryEmit(IncomingEvent(msg.event, url, sub))
                    }
                    is RelayMessage.NegMsg -> onNegMessage(msg.subscriptionId, msg.message)
                    is RelayMessage.NegErr -> negSessions.remove(msg.subscriptionId)
                    is RelayMessage.Ok -> mutableAcks.tryEmit(RelayAck(msg.eventId, url, msg.accepted, msg.message))
                    is RelayMessage.Eose -> {
                        if (fetchSubs.remove(msg.subscriptionId) != null) {
                            send(ClientMessage.Close(msg.subscriptionId).toJson())
                        } else {
                            lastEose[msg.subscriptionId] = now() / 1000
                        }
                        mutableEose.tryEmit(url to msg.subscriptionId)
                    }
                    is RelayMessage.Closed, is RelayMessage.Notice, is RelayMessage.Auth, null -> Unit
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                scheduleReconnect()
            }
        }
    }

    companion object {
        fun normalize(url: String): String = url.trim().trimEnd('/')

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
