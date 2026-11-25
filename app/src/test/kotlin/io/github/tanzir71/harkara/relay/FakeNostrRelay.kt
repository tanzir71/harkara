package io.github.tanzir71.harkara.relay

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.relay.ClientMessage
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.relay.RelayMessage
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A NIP-01 relay on OkHttp's MockWebServer: stores valid events (OK true/false), answers REQ with
 * stored matches then EOSE, pushes live matches, handles CLOSE (CLOSED), drops expired events (NIP-40),
 * and serves NIP-11 on plain HTTP with `Accept: application/nostr+json`. No real network.
 */
class FakeNostrRelay(private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    val server = MockWebServer()
    val events = CopyOnWriteArrayList<Event>()
    private val sockets = CopyOnWriteArrayList<Peer>()
    var rejectAll = false
    /** Advertise and answer NIP-77 negentropy. */
    var supportsNegentropy = false

    val url: String get() = server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/')

    fun start(): FakeNostrRelay {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) {
                    MockResponse().withWebSocketUpgrade(Peer())
                } else {
                    MockResponse().setHeader("Content-Type", "application/nostr+json").setBody(
                        """{"name":"fake","supported_nips":[1,11,40${if (supportsNegentropy) ",77" else ""}],"limitation":{"max_message_length":65536,"max_subscriptions":20,"max_filters":10}}""",
                    )
                }
        }
        server.start()
        return this
    }

    fun shutdown() = server.shutdown()

    /** Drops every open socket (the pool must reconnect). */
    fun dropConnections() = sockets.forEach { it.socket?.cancel() }

    val connectedClients: Int get() = sockets.count { it.socket != null }

    @Volatile var negOpens = 0

    private inner class Peer : WebSocketListener() {
        var socket: WebSocket? = null
        private val subs = mutableMapOf<String, List<Filter>>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            sockets += this
        }

        private val negSessions = mutableMapOf<String, io.github.tanzir71.harkara.core.relay.Negentropy>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (supportsNegentropy && handleNegentropy(webSocket, text)) return
            when (val msg = ClientMessage.parse(text)) {
                is ClientMessage.Publish -> {
                    val e = msg.event
                    val ok = !rejectAll && e.validate() && !e.isExpired(nowSeconds())
                    webSocket.send(RelayMessage.Ok(e.id, ok, if (ok) "" else "invalid: rejected").toJson())
                    if (ok && events.none { it.id == e.id }) {
                        events += e
                        sockets.forEach { peer -> peer.pushLive(e) }
                    }
                }
                is ClientMessage.Req -> {
                    subs[msg.subscriptionId] = msg.filters
                    events.filter { e -> !e.isExpired(nowSeconds()) && msg.filters.any { it.matches(e) } }
                        .sortedByDescending { it.createdAt }
                        .forEach { webSocket.send(RelayMessage.EventMessage(msg.subscriptionId, it).toJson()) }
                    webSocket.send(RelayMessage.Eose(msg.subscriptionId).toJson())
                }
                is ClientMessage.Close -> {
                    subs.remove(msg.subscriptionId)
                    webSocket.send(RelayMessage.Closed(msg.subscriptionId, "").toJson())
                }
                null -> webSocket.send(RelayMessage.Notice("could not parse").toJson())
            }
        }

        private fun handleNegentropy(webSocket: WebSocket, text: String): Boolean {
            val neg = io.github.tanzir71.harkara.core.relay.NegMessages
            neg.parseOpen(text)?.let { (id, filter, first) ->
                val items = events.filter { !it.isExpired(nowSeconds()) && filter.matches(it) }
                    .map { io.github.tanzir71.harkara.core.relay.Negentropy.Item.of(it.createdAt, it.id) }
                val session = io.github.tanzir71.harkara.core.relay.Negentropy(items)
                negSessions[id] = session
                webSocket.send(RelayMessage.NegMsg(id, session.reconcile(first)!!).toJson())
                negOpens++
                return true
            }
            neg.parseMsg(text)?.let { (id, msg) ->
                val session = negSessions[id] ?: return true.also { webSocket.send(RelayMessage.NegErr(id, "closed: unknown").toJson()) }
                webSocket.send(RelayMessage.NegMsg(id, session.reconcile(msg)!!).toJson())
                return true
            }
            if (text.startsWith("[\"NEG-CLOSE\"")) return true
            return false
        }

        fun pushLive(e: Event) {
            subs.forEach { (id, filters) -> if (filters.any { it.matches(e) }) socket?.send(RelayMessage.EventMessage(id, e).toJson()) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            sockets -= this
            socket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= this
            socket = null
        }
    }
}
