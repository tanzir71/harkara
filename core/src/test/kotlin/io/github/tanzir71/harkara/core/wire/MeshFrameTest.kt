package io.github.tanzir71.harkara.core.wire

import io.github.tanzir71.harkara.core.TestData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeshFrameTest {
    @Test
    fun `event frame round trips and the hop count sits outside the signature`() {
        val event = TestData.post(TestData.keys(1))
        val frame = MeshFrame.event(event)

        val relayed = MeshFrame.decode(frame.encode()).forRelay().forRelay()

        assertEquals(FrameType.EVENT, relayed.type)
        assertEquals(7, relayed.ttl)
        assertEquals(2, relayed.hops)
        assertArrayEquals(frame.payload, relayed.payload)
        assertEquals(event, relayed.event())
        assertTrue(relayed.event().verify())
    }

    @Test
    fun `relay stops at the ttl`() {
        var frame = MeshFrame.event(TestData.post(TestData.keys(2)), ttl = 3)
        assertTrue(frame.canRelay)
        frame = frame.forRelay()
        assertTrue(frame.canRelay)
        frame = frame.forRelay()
        assertFalse(frame.canRelay)
        assertFalse(MeshFrame.digest(emptyList()).canRelay)
    }

    @Test
    fun `digest and pull frames carry event ids`() {
        val ids = (1..3).map { TestData.post(TestData.keys(it)).id }
        assertEquals(ids, MeshFrame.decode(MeshFrame.digest(ids).encode()).ids())
        assertEquals(ids, MeshFrame.decode(MeshFrame.pull(ids).encode()).ids())
        assertEquals(1 + 3 * 32, MeshFrame.digest(ids).payloadSize)
    }

    @Test
    fun `presence frames carry the daily id`() {
        val id = ByteArray(16) { it.toByte() }
        val frame = MeshFrame.decode(MeshFrame.presence(id).encode())
        assertEquals(FrameType.PRESENCE, frame.type)
        assertArrayEquals(id, frame.payload)
    }

    @Test
    fun `bad frames are rejected`() {
        val bytes = MeshFrame.event(TestData.post(TestData.keys(3))).encode()
        assertThrows(IllegalArgumentException::class.java) { MeshFrame.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { MeshFrame.decode(byteArrayOf(2) + bytes.copyOfRange(1, bytes.size)) }
        assertThrows(IllegalArgumentException::class.java) { MeshFrame.decode(byteArrayOf(1, 99, 7, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { MeshFrame.digest(listOf("not-an-id")) }
    }
}
