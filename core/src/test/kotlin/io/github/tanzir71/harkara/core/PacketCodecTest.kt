package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PacketCodecTest {
    @Test
    fun `binary codec round trips signed packet fields`() {
        val packet = MeshPacket(
            version = 1,
            type = PacketType.POST,
            ttl = 7,
            flags = PacketFlags.SIGNED,
            msgId = 0x1122334455667788L,
            senderId = NearId(ByteArray(16) { it.toByte() }),
            timestamp = Instant.ofEpochMilli(1_725_000_123_000),
            payload = "hello Harkara".encodeToByteArray(),
            signature = ByteArray(64) { (255 - it).toByte() },
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(packet.version, decoded.version)
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.ttl, decoded.ttl)
        assertEquals(packet.flags, decoded.flags)
        assertEquals(packet.msgId, decoded.msgId)
        assertEquals(packet.senderId, decoded.senderId)
        assertEquals(packet.timestamp, decoded.timestamp)
        assertArrayEquals(packet.payload, decoded.payload)
        assertArrayEquals(packet.signature, decoded.signature)
    }

    @Test
    fun `relay decrements ttl and preserves message identity`() {
        val packet = MeshPacket.chat(
            msgId = 42,
            senderId = NearId(ByteArray(16) { 7 }),
            payload = "mesh".encodeToByteArray(),
            now = Instant.ofEpochMilli(100),
        )

        val relayed = packet.forRelay()

        assertEquals(6, relayed.ttl)
        assertEquals(packet.msgId, relayed.msgId)
        assertEquals(packet.senderId, relayed.senderId)
        assertArrayEquals(packet.payload, relayed.payload)
    }

    @Test
    fun `packet size includes signature only when signed flag is set`() {
        val unsigned = MeshPacket.chat(
            msgId = 1,
            senderId = NearId(ByteArray(16)),
            payload = ByteArray(12),
            now = Instant.EPOCH,
        )
        val signed = unsigned.copy(flags = PacketFlags.SIGNED, signature = ByteArray(64))

        assertEquals(PacketCodec.HEADER_SIZE + 12, PacketCodec.encode(unsigned).size)
        assertEquals(PacketCodec.HEADER_SIZE + 12 + 64, PacketCodec.encode(signed).size)
    }

    @Test
    fun `decode rejects payload length mismatch`() {
        val bytes = PacketCodec.encode(
            MeshPacket.chat(
                msgId = 9,
                senderId = NearId(ByteArray(16)),
                payload = ByteArray(1),
                now = Instant.EPOCH,
            ),
        )
        bytes[36] = 0
        bytes[37] = 5

        val result = runCatching { PacketCodec.decode(bytes) }

        assertTrue(result.isFailure)
    }
}
