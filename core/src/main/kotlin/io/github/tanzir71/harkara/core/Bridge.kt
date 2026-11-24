package io.github.tanzir71.harkara.core

import java.time.Instant

/** Relay client used by BridgeEngine for opportunistic internet sync. */
interface RelayClient {
    /** Uploads locally cached posts to the relay. */
    suspend fun upload(posts: List<SocialPost>)

    /** Downloads relay posts matching prefixes and newer than [since]. */
    suspend fun backfill(prefixes: Set<String>, since: Instant): List<SocialPost>
}

/** Core hybrid-sync bridge between local store-and-forward and internet relay. */
class BridgeEngine(
    private val cache: StoreForwardCache,
    private val relay: RelayClient,
    private val subscribedPrefixes: Set<String>,
) {
    private var lastSync: Instant = Instant.EPOCH

    /** Uploads local cache and downloads matching relay items. */
    suspend fun sync(now: Instant) {
        val digest = cache.digestFor(subscribedPrefixes, now)
        relay.upload(cache.pull(digest.postIds))
        val downloaded = relay.backfill(subscribedPrefixes, lastSync)
        cache.ingest(downloaded)
        lastSync = now
    }
}

/** In-memory relay stub for deterministic JVM tests. */
class InMemoryRelayClient : RelayClient {
    private val posts = linkedMapOf<String, SocialPost>()

    override suspend fun upload(posts: List<SocialPost>) {
        posts.forEach { post -> this.posts[post.postId] = post }
    }

    override suspend fun backfill(prefixes: Set<String>, since: Instant): List<SocialPost> =
        posts.values
            .filter { post -> post.createdAt.isAfter(since) && prefixes.any { post.geohash.startsWith(it) } }
            .sortedBy { it.createdAt }
}
