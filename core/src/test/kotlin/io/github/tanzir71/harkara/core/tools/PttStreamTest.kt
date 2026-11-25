package io.github.tanzir71.harkara.core.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PttStreamTest {
    @Test fun `off and released gates produce no audio and each hold has a byte and wall time cap`() {
        var now = 0L
        val gate = HeldPttStream { now }
        assertNull(gate.next(ByteArray(2)))
        assertFalse(gate.isHeld)
        gate.press()
        val first = gate.next(ByteArray(PttFormat.CHUNK_BYTES))!!
        assertEquals(0, first.sequence)
        repeat(74) { assertNotNull(gate.next(ByteArray(PttFormat.CHUNK_BYTES))) }
        assertNull(gate.next(ByteArray(2)))
        gate.release()
        gate.press()
        now = 15_001
        assertNull(gate.next(ByteArray(2)))
        gate.release()
        assertFalse(gate.isHeld)
    }

    @Test fun `receiver requires explicit paired peer consent rejects replays gaps oversized and expired chunks`() {
        var now = 10L
        val played = mutableListOf<PttChunk>()
        val receiver = PttReceiver({ played += it }) { now }
        val chunk = PttChunk("stream", 0, ByteArray(6400))
        assertFalse(receiver.receive("127.0.0.1", "", chunk))
        val token = receiver.enable("127.0.0.1")
        assertFalse(receiver.receive("127.0.0.2", token, chunk))
        assertFalse(receiver.receive("127.0.0.1", "wrong", chunk))
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(sequence = 1)))
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(pcm = ByteArray(6402))))
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(pcm = ByteArray(3))))
        assertTrue(receiver.receive("127.0.0.1", token, chunk))
        assertFalse(receiver.receive("127.0.0.1", token, chunk))
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(sequence = 2)))
        assertTrue(receiver.receive("127.0.0.1", token, chunk.copy(sequence = 1)))
        now += 15_001
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(sequence = 2)))
        assertTrue(receiver.receive("127.0.0.1", token, chunk.copy(streamId = "next")))
        assertFalse(receiver.receive("127.0.0.1", token, chunk))
        receiver.disable()
        assertFalse(receiver.receive("127.0.0.1", token, chunk.copy(streamId = "third")))
        assertEquals(3, played.size)
    }

    @Test fun `listening expires and replay memory is bounded without accepting evicted streams`() {
        var now = 0L
        val receiver = PttReceiver({}) { now }
        val token = receiver.enable("peer", 1000)
        repeat(64) { assertTrue(receiver.receive("peer", token, PttChunk("s$it", 0, ByteArray(2)))) }
        assertFalse(receiver.receive("peer", token, PttChunk("s64", 0, ByteArray(2))))
        assertFalse(receiver.receive("peer", token, PttChunk("s0", 0, ByteArray(2))))
        now = 1001
        assertFalse(receiver.receive("peer", token, PttChunk("s63", 1, ByteArray(2))))
    }
}
