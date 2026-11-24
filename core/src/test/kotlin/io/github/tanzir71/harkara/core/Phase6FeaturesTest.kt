package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class Phase6FeaturesTest {
    @Test
    fun `media compressor keeps mesh image payload under forty eight kilobytes`() {
        val source = ByteArray(100_000) { it.toByte() }

        val compressed = MediaCompressor.compressForMesh(source)

        assertEquals("image/webp", compressed.mimeType)
        assertTrue(compressed.bytes.size <= 48 * 1024)
        assertTrue(compressed.blurHash.isNotBlank())
    }

    @Test
    fun `reaction counter merges per author maximum counts`() {
        val postId = "p1"
        val alice = NearId(ByteArray(16) { 1 })
        val bob = NearId(ByteArray(16) { 2 })
        val counter = ReactionCounter()

        counter.merge(ReactionEvent(postId, alice, ReactionKind.LIKE, count = 1))
        counter.merge(ReactionEvent(postId, alice, ReactionKind.LIKE, count = 2))
        counter.merge(ReactionEvent(postId, alice, ReactionKind.LIKE, count = 1))
        counter.merge(ReactionEvent(postId, bob, ReactionKind.LIKE, count = 1))

        assertEquals(3, counter.total(postId, ReactionKind.LIKE))
    }

    @Test
    fun `ack state machine advances monotonically`() {
        val machine = AckStateMachine()
        val msgId = 44L

        assertEquals(DeliveryState.PENDING, machine.stateOf(msgId))
        machine.markMeshDelivered(msgId)
        machine.markPending(msgId)
        machine.markAcked(msgId)
        machine.markMeshDelivered(msgId)

        assertEquals(DeliveryState.ACKED, machine.stateOf(msgId))
    }

    @Test
    fun `channel payload filters broadcast chat by hashtag channel`() {
        val packet = MeshPacket.chat(
            msgId = 7,
            senderId = NearId(ByteArray(16) { 7 }),
            payload = ChannelMessage(channel = "#market", body = "rice at stall 4").toPayload(),
            now = Instant.EPOCH,
        )

        assertTrue(ChannelFilter("#market").matches(packet))
        assertEquals("rice at stall 4", ChannelMessage.fromPayload(packet.payload).body)
    }
}
