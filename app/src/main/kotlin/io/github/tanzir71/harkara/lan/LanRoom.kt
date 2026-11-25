package io.github.tanzir71.harkara.lan

import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.lan.HttpHandler
import io.github.tanzir71.harkara.core.lan.LanRelayServer
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.relay.LocalRelay
import io.github.tanzir71.harkara.relay.RelayAck
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.SecureRandom

/**
 * "Everyone on this Wi-Fi": every phone in the room runs a small room server ([LanRelayServer] around a
 * [LocalRelay]) advertised over [LanDiscovery], and connects a second relay pool to every other phone's
 * server. The room carries gift wraps (DMs, groups, receipts) and the room's own kind 20000 messages, so
 * chats keep working on a Wi-Fi with no internet. It runs only while the app is in the foreground, or while
 * this phone is a neighbourhood [node] (foreground service): a node's server keeps every kind of event for the
 * room and serves the download page, APK, map packs and handbook over plain HTTP.
 */
class LanRoom(
    private val scope: CoroutineScope,
    private val discovery: LanDiscovery,
    private val bindAddress: InetAddress? = null,
    now: () -> Long = System::currentTimeMillis,
    val pool: RelayPool = RelayPool(scope, now = now),
    private val accept: (Event) -> Boolean = { true },
) {
    /** This phone is a neighbourhood node: its room keeps posts, tasks and SOS too, not only chats. */
    @Volatile var node: Boolean = false

    private val rateLimits = io.github.tanzir71.harkara.core.mesh.PeerRateLimiter()
    private val sosPolicy = io.github.tanzir71.harkara.core.SosPolicy()
    val relay = LocalRelay(maxEvents = 5_000, nowSeconds = { now() / 1000 }, accept = { e ->
        (node || ChatProtocol.carriedByLanRoom(e)) && e.createdAt <= now() / 1000 + 900 &&
            accept(e) && rateLimits.tryAccept(e) && sosPolicy.accept(e)
    })
    private val mutableIncoming = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    private val server = LanRelayServer(relay, onEvent = { mutableIncoming.tryEmit(it) })
    private val mutableRunning = MutableStateFlow(false)
    private var peersJob: Job? = null

    /** Events from the room: published to our server by peers, or sent by peers' servers. */
    val incoming: SharedFlow<Event> = mutableIncoming.asSharedFlow()

    /** OKs from peers' servers for what we published. */
    val acks: SharedFlow<RelayAck> get() = pool.acks

    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** Phones in the room with us: the larger of the servers we reach and the peers connected to ours. */
    val peerCount: StateFlow<Int> = combine(pool.status, server.clientCount) { status, inbound ->
        maxOf(status.values.count { it == RelayStatus.CONNECTED }, inbound)
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    val port: Int get() = server.port

    /** Plain HTTP on the room server (download page and files); null answers with a one-line page. */
    var http: HttpHandler?
        get() = server.http
        set(value) {
            server.http = value
        }

    /** Neighbourhood nodes in the room that our pool is connected to. */
    val nodePeers: StateFlow<Int> = combine(discovery.peers, pool.status) { peers, status ->
        peers.count { it.node && status[RelayPool.normalize(it.url)] == RelayStatus.CONNECTED }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch { pool.incoming.collect { mutableIncoming.emit(it.event) } }
    }

    @Synchronized
    fun start() {
        if (mutableRunning.value) return
        val port = server.start(bindAddress)
        discovery.start((if (node) NODE_PREFIX else SERVICE_PREFIX) + Hex.encode(ByteArray(4).also(SecureRandom()::nextBytes)), port)
        peersJob = scope.launch {
            discovery.peers.collect { peers -> pool.setRelays(peers.map { it.url }) }
        }
        mutableRunning.value = true
    }

    @Synchronized
    fun stop() {
        if (!mutableRunning.value) return
        peersJob?.cancel()
        peersJob = null
        discovery.stop()
        pool.stop()
        server.stop()
        mutableRunning.value = false
    }

    /**
     * Puts [event] in our own room (connected peers get it live, late ones pull it) and sends it to every
     * peer's server. Returns how many connections it was written to.
     */
    suspend fun publish(event: Event): Int {
        if (!mutableRunning.value) return 0
        return withContext(Dispatchers.IO) {
            server.inject(event)
            pool.publish(event) + server.clientCount.value
        }
    }

    /** Re-advertises after [node] changed (the service name says whether this phone is a node). */
    fun restart() {
        stop()
        start()
    }

    /** Sends [event] to the neighbourhood nodes in the room only (posts, tasks, SOS). Returns how many took it. */
    suspend fun publishToNodes(event: Event): Int {
        if (!mutableRunning.value) return 0
        val nodes = discovery.peers.value.filter { it.node }.map { it.url }
        if (nodes.isEmpty()) return 0
        return withContext(Dispatchers.IO) { pool.publish(event, nodes) }
    }

    fun subscribe(id: String, filters: List<Filter>, resumeSlackSeconds: Long = 0) = pool.subscribe(id, filters, resumeSlackSeconds)

    companion object {
        /** NSD service names are random per session, never the user's key. */
        const val SERVICE_PREFIX = "harkara-"

        /** Prefix of a neighbourhood node's service name. */
        const val NODE_PREFIX = "harkara-node-"
    }
}
