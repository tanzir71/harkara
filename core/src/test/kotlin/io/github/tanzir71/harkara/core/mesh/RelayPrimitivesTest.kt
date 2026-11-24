package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.MeshFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RelayPrimitivesTest {
    private val now = Instant.parse("2026-11-25T06:00:00Z")

    @Test
    fun `dedupe by event id until expiry`() {
        val cache = DedupeCache(capacity = 3, expiry = Duration.ofMinutes(30))
        val id = TestData.post(TestData.keys(1)).id

        assertTrue(cache.markIfNew(id, now))
        assertFalse(cache.markIfNew(id, now.plusSeconds(1)))
        assertTrue(cache.contains(id, now.plusSeconds(60)))
        assertTrue(cache.markIfNew(id, now.plus(Duration.ofMinutes(31))))
    }

    @Test
    fun `dedupe evicts the least recently seen id at capacity`() {
        val cache = DedupeCache(capacity = 2, expiry = Duration.ofMinutes(30))
        val ids = (1..3).map { TestData.post(TestData.keys(it)).id }

        ids.forEachIndexed { i, id -> assertTrue(cache.markIfNew(id, now.plusMillis(i.toLong()))) }

        assertEquals(2, cache.size)
        assertTrue(cache.markIfNew(ids[0], now.plusMillis(3)))
    }

    @Test
    fun `token bucket refills by elapsed time`() {
        val bucket = TokenBucket(ratePerSecond = 2.0, capacity = 2)

        assertTrue(bucket.tryConsume(now))
        assertTrue(bucket.tryConsume(now))
        assertFalse(bucket.tryConsume(now))
        assertTrue(bucket.tryConsume(now.plusMillis(500)))
    }

    @Test
    fun `priority queue keeps sos and dms and drops sync then posts first`() {
        val keys = TestData.keys(2)
        val sos = MeshFrame.event(keys.signEvent(Kinds.SOS, content = "help"))
        val dm = MeshFrame.event(GiftWrap.sealAndWrap(GiftWrap.createRumor(keys.pubkeyHex, Kinds.CHAT_MESSAGE, "hi"), keys, TestData.keys(3).pubkeyHex))
        val chat = MeshFrame.event(keys.signEvent(Kinds.AREA_CHAT, listOf(listOf("g", "wh0r0")), "anyone nearby?"))
        val post = MeshFrame.event(TestData.post(keys))
        val sync = MeshFrame.digest(emptyList())
        val queue = OutboundRelayQueue(capacity = 3)

        listOf(sync, post, chat, dm, sos).forEach { queue.offer(it) }

        assertEquals(listOf(sos, dm, chat), queue.drain())
        assertEquals(0, queue.size)
    }

    @Test
    fun `lower priority work is refused when the queue is full of higher`() {
        val keys = TestData.keys(4)
        val queue = OutboundRelayQueue(capacity = 1)
        val sos = MeshFrame.event(keys.signEvent(Kinds.SOS, content = "help"))
        assertTrue(queue.offer(sos))
        assertFalse(queue.offer(MeshFrame.event(TestData.post(keys))))
        assertEquals(listOf(sos), queue.drain())
    }

    @Test
    fun `priority order`() {
        assertTrue(MeshPriority.ofKind(Kinds.SOS) > MeshPriority.ofKind(Kinds.GIFT_WRAP))
        assertTrue(MeshPriority.ofKind(Kinds.GIFT_WRAP) > MeshPriority.ofKind(Kinds.AREA_CHAT))
        assertTrue(MeshPriority.ofKind(Kinds.AREA_CHAT) > MeshPriority.ofKind(Kinds.FEED_POST))
        assertTrue(MeshPriority.ofKind(Kinds.FEED_POST) > MeshPriority.of(MeshFrame.pull(emptyList())))
        assertEquals(MeshPriority.SOS, MeshPriority.ofKind(Kinds.CHECK_IN))
    }
}
