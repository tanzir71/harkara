package io.github.tanzir71.harkara.core.lan

import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.relay.ClientMessage
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.relay.LocalRelay
import io.github.tanzir71.harkara.core.relay.RelayMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The Wi-Fi room server on localhost, driven by the JDK's own WebSocket client (no fakes in between). */
class LanRelayServerTest {
    private val received = CopyOnWriteArrayList<Event>()
    private val relay = LocalRelay(accept = ChatProtocol::carriedByLanRoom)
    private val server = LanRelayServer(relay, onEvent = { received += it })
    private val http = HttpClient.newHttpClient()
    private val sockets = mutableListOf<WebSocket>()

    @AfterEach
    fun tearDown() {
        sockets.forEach { runCatching { it.abort() } }
        server.stop()
    }

    private inner class Peer : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        val pongs = LinkedBlockingQueue<ByteBuffer>()
        private val partial = StringBuilder()
        lateinit var socket: WebSocket

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                messages.put(partial.toString())
                partial.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            pongs.put(message)
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        fun send(text: String) = socket.sendText(text, true).get(5, TimeUnit.SECONDS)

        fun next(): RelayMessage = RelayMessage.parse(messages.poll(5, TimeUnit.SECONDS) ?: error("nothing received"))!!
    }

    private fun connect(port: Int): Peer {
        val peer = Peer()
        peer.socket = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port"), peer).get(5, TimeUnit.SECONDS)
        sockets += peer.socket
        return peer
    }

    @Test
    fun `peers exchange gift wraps and room messages through the server`() {
        val port = server.start(InetAddress.getLoopbackAddress())
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val bobPeer = connect(port)
        val alicePeer = connect(port)
        assertEquals(2, waitFor { server.clientCount.value == 2 }.let { server.clientCount.value })

        bobPeer.send(ClientMessage.Req("dm", listOf(Filter(kinds = listOf(Kinds.GIFT_WRAP), tags = mapOf("p" to listOf(bob.pubkeyHex))))).toJson())
        assertTrue(bobPeer.next() is RelayMessage.Eose)

        val wrap = GiftWrap.sealAndWrap(ChatProtocol.textRumor(alice.pubkeyHex, listOf(bob.pubkeyHex), "Generator on in the hall?", 1_764_000_000), alice, bob.pubkeyHex)
        alicePeer.send(ClientMessage.Publish(wrap).toJson())
        val ok = alicePeer.next() as RelayMessage.Ok
        assertTrue(ok.accepted)
        val live = bobPeer.next() as RelayMessage.EventMessage
        assertEquals(wrap.id, live.event.id)
        assertEquals("Generator on in the hall?", GiftWrap.unwrap(live.event, bob).content)
        // Socket delivery and this phone's inbox callback complete independently.
        waitFor { received.size == 1 }
        assertEquals(listOf(wrap.id), received.map { it.id })

        // The room keeps wraps for late joiners, but refuses kinds it does not carry.
        val late = connect(port)
        late.send(ClientMessage.Req("late", listOf(Filter(tags = mapOf("p" to listOf(bob.pubkeyHex))))).toJson())
        assertEquals(wrap.id, (late.next() as RelayMessage.EventMessage).event.id)
        val note = alice.signEvent(Kinds.FEED_POST, emptyList(), "not for this room")
        alicePeer.send(ClientMessage.Publish(note).toJson())
        assertTrue(!(alicePeer.next() as RelayMessage.Ok).accepted)
        assertEquals(1, received.size)

        // This phone's own room message reaches subscribed peers but is not stored (ephemeral).
        bobPeer.send(ClientMessage.Req("room", listOf(Filter(kinds = listOf(Kinds.AREA_CHAT), tags = mapOf("t" to listOf("wifi"))))).toJson())
        assertTrue(bobPeer.next() is RelayMessage.Eose)
        val roomMessage = ChatProtocol.wifiMessage(alice, "Tea in the common room", System.currentTimeMillis() / 1000)
        server.inject(roomMessage)
        assertEquals(roomMessage.id, (bobPeer.next() as RelayMessage.EventMessage).event.id)
        assertEquals(1, relay.storedCount)
    }

    @Test
    fun `serves NIP-11 and plain HTTP, answers pings, frees closed clients`() {
        val port = server.start(InetAddress.getLoopbackAddress())
        val info = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).header("Accept", "application/nostr+json").timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, info.statusCode())
        assertTrue(info.body().contains("\"supported_nips\""))
        val page = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
        assertTrue(page.body().startsWith("Harkara LAN room"))

        val peer = connect(port)
        peer.socket.sendPing(ByteBuffer.wrap(byteArrayOf(1, 2, 3))).get(5, TimeUnit.SECONDS)
        val pong = peer.pongs.poll(5, TimeUnit.SECONDS)!!
        assertEquals(3, pong.remaining())

        // A long message split across continuation frames is put back together.
        val big = "x".repeat(70_000)
        peer.socket.sendText(big.substring(0, 30_000), false).get(5, TimeUnit.SECONDS)
        peer.socket.sendText(big.substring(30_000), true).get(5, TimeUnit.SECONDS)
        assertTrue(peer.next() is RelayMessage.Notice)

        waitFor { server.clientCount.value == 1 }
        peer.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
        waitFor { server.clientCount.value == 0 }
        assertEquals(0, server.clientCount.value)
        server.stop()
        assertTrue(!server.isRunning)
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }
}
