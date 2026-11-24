package io.github.tanzir71.harkara.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BridgeEngineTest {
    @Test
    fun `mesh only post reaches internet only node through bridge relay`() = runTest {
        val now = Instant.ofEpochMilli(1_000)
        val relay = InMemoryRelayClient()
        val bridgeCache = StoreForwardCache(subscribedPrefixes = setOf("9q8yy"))
        val internetOnlyCache = StoreForwardCache(subscribedPrefixes = setOf("9q8yy"))
        val post = SocialPost(
            postId = "mesh-post",
            author = NearId(ByteArray(16) { 3 }),
            displayName = "Aster",
            text = "clinic has power",
            geohash = "9q8yyk",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            signature = ByteArray(64),
        )

        bridgeCache.ingest(post)
        BridgeEngine(bridgeCache, relay, subscribedPrefixes = setOf("9q8yy")).sync(now)
        BridgeEngine(internetOnlyCache, relay, subscribedPrefixes = setOf("9q8yy")).sync(now)

        assertEquals(listOf("mesh-post"), internetOnlyCache.feed.query("9q8yy", now).map { it.postId })
    }
}
