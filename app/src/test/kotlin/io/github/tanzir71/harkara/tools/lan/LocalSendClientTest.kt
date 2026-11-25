package io.github.tanzir71.harkara.tools.lan

import io.github.tanzir71.harkara.core.tools.LocalSendDevice
import io.github.tanzir71.harkara.core.tools.LocalSendFile
import io.github.tanzir71.harkara.core.tools.LocalSendReceived
import io.github.tanzir71.harkara.core.tools.LocalSendServer
import io.github.tanzir71.harkara.core.tools.PttChunk
import io.github.tanzir71.harkara.core.tools.PttReceiver
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** Only fake peers and loopback HTTP; no internet or Android radio/microphone. */
class LocalSendClientTest {
    @get:Rule val temp = TemporaryFolder()
    private val device = LocalSendDevice("Rafi", fingerprint = "not-authentication")
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun `two phones transfer selected files through the concrete HTTP transport`() {
        val received = CopyOnWriteArrayList<LocalSendReceived>()
        LocalSendServer(device, temp.newFolder(), consent = { CompletableFuture.completedFuture(setOf("a")) },
            onReceived = { received += it }).use { server ->
            val peer = LanToolPeer(loopback, server.start(loopback, 0))
            LocalSendClient().use { client ->
                assertEquals(device.alias, client.info(peer).alias)
                val bytes = ByteArray(70_000) { (it % 251).toByte() }
                val result = client.send(peer, device, listOf(
                    OutgoingLanFile(LocalSendFile("a", "map.pmtiles", bytes.size.toLong())) { bytes.inputStream() },
                    OutgoingLanFile(LocalSendFile("b", "not-selected", 1)) { error("Unselected source must never be opened") },
                ))
                assertEquals(setOf("a"), result.sent)
                assertEquals(setOf("b"), result.declined)
                assertArrayEquals(bytes, received.single().file.readBytes())
            }
        }
    }

    @Test fun `denied consent and closed client never open source streams`() {
        LocalSendServer(device, temp.newFolder()).use { server ->
            val peer = LanToolPeer(loopback, server.start(loopback, 0))
            val file = OutgoingLanFile(LocalSendFile("a", "secret", 10)) { error("No permission to open source") }
            LocalSendClient().use { client ->
                assertThrows(IOException::class.java) { client.send(peer, device, listOf(file)) }
                client.close()
                assertThrows(IOException::class.java) { client.info(peer) }
            }
        }
    }

    @Test fun `public destinations redirects and oversized responses are refused`() {
        assertThrows(IllegalArgumentException::class.java) { LanToolPeer(InetAddress.getByName("8.8.8.8"), 80) }
        MockWebServer().use { fake ->
            fake.start(loopback, 0)
            val peer = LanToolPeer(loopback, fake.port)
            LocalSendClient().use { client ->
                fake.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"))
                assertThrows(IOException::class.java) { client.info(peer) }
                fake.enqueue(MockResponse().setBody("x".repeat(65_537)))
                assertThrows(IOException::class.java) { client.info(peer) }
                assertEquals(2, fake.requestCount)
            }
        }
    }

    @Test fun `paired PTT crosses same dedicated listener and stops accepting when off`() {
        val played = CopyOnWriteArrayList<PttChunk>()
        val receiver = PttReceiver({ played += it })
        LocalSendServer(device, temp.newFolder(), ptt = receiver).use { server ->
            val peer = LanToolPeer(loopback, server.start(loopback, 0))
            val token = receiver.enable("127.0.0.1")
            HttpPttTransport(peer, token).use { transport ->
                transport.send(PttChunk("hold", 0, ByteArray(6400)))
                transport.send(PttChunk("hold", 1, ByteArray(6400)))
                assertEquals(2, played.size)
                assertThrows(IOException::class.java) { transport.send(PttChunk("hold", 1, ByteArray(6400))) }
                receiver.disable()
                assertThrows(IOException::class.java) { transport.send(PttChunk("hold", 2, ByteArray(6400))) }
            }
            HttpPttTransport(peer, token).use { transport ->
                transport.close()
                assertThrows(IOException::class.java) { transport.send(PttChunk("other", 0, ByteArray(2))) }
            }
        }
    }
}
