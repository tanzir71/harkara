package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class FragmentationTest {
    @Test
    fun `fragmenter emits mtu bounded fragments and reassembles original packet`() {
        val original = MeshPacket(
            version = 1,
            type = PacketType.POST,
            ttl = 7,
            flags = PacketFlags.NONE,
            msgId = 500,
            senderId = NearId(ByteArray(16) { 9 }),
            timestamp = Instant.ofEpochMilli(2_000),
            payload = ByteArray(600) { (it % 251).toByte() },
        )

        val fragments = Fragmenter.fragment(original, mtu = 160)
        val reassembler = FragmentReassembler(timeout = Duration.ofSeconds(60))

        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.type == PacketType.FRAGMENT })
        assertTrue(fragments.all { PacketCodec.encode(it).size <= 160 })

        fragments.dropLast(1).forEach { fragment ->
            assertNull(reassembler.accept(fragment, Instant.ofEpochMilli(2_100)))
        }
        val reassembled = reassembler.accept(fragments.last(), Instant.ofEpochMilli(2_100))

        assertEquals(original.msgId, reassembled?.msgId)
        assertEquals(original.type, reassembled?.type)
        assertArrayEquals(original.payload, reassembled?.payload)
    }

    @Test
    fun `reassembler drops incomplete packet after timeout`() {
        val original = MeshPacket.chat(
            msgId = 700,
            senderId = NearId(ByteArray(16) { 1 }),
            payload = ByteArray(500),
            now = Instant.EPOCH,
        )
        val first = Fragmenter.fragment(original, mtu = 160).first()
        val reassembler = FragmentReassembler(timeout = Duration.ofSeconds(60))

        assertNull(reassembler.accept(first, Instant.EPOCH))
        assertNull(reassembler.accept(first, Instant.EPOCH.plusSeconds(61)))
    }
}
