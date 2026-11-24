package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RelayAndDedupeTest {
    @Test
    fun `dedupe accepts first sighting and rejects repeats until expiry`() {
        val cache = LruDedupeCache(capacity = 3, expiry = Duration.ofMinutes(30))
        val now = Instant.ofEpochMilli(1_000)

        assertTrue(cache.markIfNew(100, now))
        assertFalse(cache.markIfNew(100, now.plusSeconds(1)))
        assertTrue(cache.markIfNew(100, now.plus(Duration.ofMinutes(31))))
    }

    @Test
    fun `dedupe evicts least recently seen item when capacity is reached`() {
        val cache = LruDedupeCache(capacity = 2, expiry = Duration.ofMinutes(30))
        val now = Instant.ofEpochMilli(1_000)

        assertTrue(cache.markIfNew(1, now))
        assertTrue(cache.markIfNew(2, now.plusMillis(1)))
        assertTrue(cache.markIfNew(3, now.plusMillis(2)))

        assertTrue(cache.markIfNew(1, now.plusMillis(3)))
    }

    @Test
    fun `token bucket refills by elapsed time`() {
        val bucket = TokenBucket(ratePerSecond = 2.0, capacity = 2)
        val now = Instant.ofEpochMilli(1_000)

        assertTrue(bucket.tryConsume(now))
        assertTrue(bucket.tryConsume(now))
        assertFalse(bucket.tryConsume(now))
        assertTrue(bucket.tryConsume(now.plusMillis(500)))
    }

    @Test
    fun `priority queue drops lowest priority first`() {
        val queue = OutboundRelayQueue(capacity = 3)
        val sender = NearId(ByteArray(16) { 4 })

        queue.offer(MeshPacket.chat(1, sender, byteArrayOf(1), Instant.EPOCH))
        queue.offer(MeshPacket.chat(2, sender, byteArrayOf(2), Instant.EPOCH).copy(type = PacketType.POST))
        queue.offer(MeshPacket.chat(3, sender, byteArrayOf(3), Instant.EPOCH).copy(type = PacketType.SYNC_DIGEST))
        queue.offer(MeshPacket.chat(4, sender, byteArrayOf(4), Instant.EPOCH).copy(type = PacketType.SOS))

        assertEquals(listOf(PacketType.SOS, PacketType.CHAT_BROADCAST, PacketType.POST), queue.drain().map { it.type })
    }
}
