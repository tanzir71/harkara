package io.github.tanzir71.harkara.core.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class FakeRadioAirTest {
    @Test
    fun `handshake frames follow both peer-up callbacks for links and late starts`() {
        val air = FakeRadioAir()
        val errors = ConcurrentLinkedQueue<String>()
        val received = AtomicInteger()
        fun listener(radio: MeshRadio, name: String) = object : MeshRadio.Listener {
            private var up = false
            override fun onPeerUp(peer: String, mtu: Int) {
                up = true
                if (!radio.send(peer, byteArrayOf(42), 1)) errors += "send failed for $name"
            }
            override fun onPeerDown(peer: String) { up = false }
            override fun onBytes(peer: String, bytes: ByteArray) {
                if (!up) errors += "bytes arrived before link-ready at $name"
                received.incrementAndGet()
            }
        }
        try {
            repeat(100) { i ->
                val a = air.radio("a$i")
                val b = air.radio("b$i")
                if (i % 2 == 0) {
                    a.start(byteArrayOf(1), listener(a, "a$i"))
                    b.start(byteArrayOf(2), listener(b, "b$i"))
                    air.link("a$i", "b$i")
                } else {
                    air.link("a$i", "b$i")
                    a.start(byteArrayOf(1), listener(a, "a$i"))
                    b.start(byteArrayOf(2), listener(b, "b$i"))
                }
            }
            air.awaitIdle()
            assertTrue(errors.isEmpty(), errors.joinToString())
            assertEquals(200, received.get())
        } finally { air.shutdown() }
    }
}
