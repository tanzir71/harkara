package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FeedReplicationTest {
    @Test
    fun `geohash encoder produces six character location buckets`() {
        assertEquals("9q8yyk", GeoHash.encode(latitude = 37.7749, longitude = -122.4194, precision = 6))
    }

    @Test
    fun `feed query returns nearby non expired posts newest first`() {
        val now = Instant.ofEpochMilli(10_000)
        val store = FeedStore(subscribedPrefixes = setOf("9q8yy"))
        val old = post("old", geohash = "9q8yyk", createdAt = now.minusSeconds(50))
        val fresh = post("fresh", geohash = "9q8yym", createdAt = now)
        val foreign = post("foreign", geohash = "u4pruy", createdAt = now.plusSeconds(1))
        val expired = post("expired", geohash = "9q8yyn", createdAt = now, expiresAt = now.minusMillis(1))

        listOf(old, fresh, foreign, expired).forEach(store::ingest)

        assertEquals(listOf("fresh", "old"), store.query(prefix = "9q8yy", now = now).map { it.postId })
    }

    @Test
    fun `partition heal sync moves nearby post by digest and pull`() {
        val now = Instant.ofEpochMilli(1_000)
        val alice = StoreForwardCache(subscribedPrefixes = setOf("9q8yy"))
        val bob = StoreForwardCache(subscribedPrefixes = setOf("9q8yy"))
        val alicePost = post("p1", geohash = "9q8yyk", createdAt = now)

        alice.ingest(alicePost)
        val digest = alice.digestFor(prefixes = setOf("9q8yy"), now = now)
        val wanted = bob.wantedFrom(digest)
        bob.ingest(alice.pull(wanted))

        assertEquals(listOf("p1"), bob.feed.query(prefix = "9q8yy", now = now).map { it.postId })
    }

    @Test
    fun `foreign geohash relays but is not cached`() {
        val cache = StoreForwardCache(subscribedPrefixes = setOf("9q8yy"))
        val foreign = post("foreign", geohash = "u4pruy", createdAt = Instant.EPOCH)

        val result = cache.ingest(foreign)

        assertTrue(result.shouldRelay)
        assertEquals(emptyList<SocialPost>(), cache.feed.query(prefix = "u4pru", now = Instant.EPOCH))
    }

    private fun post(
        id: String,
        geohash: String,
        createdAt: Instant,
        expiresAt: Instant = createdAt.plusSeconds(72 * 60 * 60),
    ): SocialPost = SocialPost(
        postId = id,
        author = NearId(ByteArray(16) { 8 }),
        displayName = "Aster",
        text = id,
        geohash = geohash,
        createdAt = createdAt,
        expiresAt = expiresAt,
        signature = ByteArray(64),
    )
}
