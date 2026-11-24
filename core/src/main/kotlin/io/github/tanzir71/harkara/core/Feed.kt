package io.github.tanzir71.harkara.core

import java.time.Instant

/** Geohash encoding helpers for location-scoped feeds. */
object GeoHash {
    /** Encodes a latitude/longitude into a geohash of [precision] characters. */
    fun encode(latitude: Double, longitude: Double, precision: Int = 6): String {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(precision > 0) { "precision must be positive" }
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var even = true
        var bits = 0
        var value = 0
        val result = StringBuilder()
        while (result.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (longitude >= mid) {
                    value = (value shl 1) or 1
                    lonMin = mid
                } else {
                    value = value shl 1
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    value = (value shl 1) or 1
                    latMin = mid
                } else {
                    value = value shl 1
                    latMax = mid
                }
            }
            even = !even
            bits += 1
            if (bits == 5) {
                result.append(BASE32[value])
                bits = 0
                value = 0
            }
        }
        return result.toString()
    }

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
}

/** Signed social post scoped to a geohash. */
data class SocialPost(
    /** Stable post id. */
    val postId: String,
    /** Author identity. */
    val author: NearId,
    /** Display name attached at creation time. */
    val displayName: String,
    /** Post text, limited to 512 chars. */
    val text: String,
    /** Six-character geohash. */
    val geohash: String,
    /** Creation time. */
    val createdAt: Instant,
    /** Expiration time. */
    val expiresAt: Instant,
    /** Ed25519 signature over post content. */
    val signature: ByteArray,
    /** Feed post kind. */
    val kind: PostKind = PostKind.STANDARD,
    /** Optional event start time. */
    val eventStartTime: Instant? = null,
) {
    init {
        require(text.length <= 512) { "post text exceeds 512 chars" }
        require(geohash.length >= 6) { "post geohash must include at least 6 chars" }
    }
}

/** Social post subtype. */
enum class PostKind {
    STANDARD,
    BULLETIN,
    EVENT,
}

/** In-memory feed cache used by protocol tests and adapters. */
class FeedStore(private val subscribedPrefixes: Set<String>) {
    private val posts = linkedMapOf<String, SocialPost>()

    /** Caches a post when its geohash matches subscribed prefixes. */
    fun ingest(post: SocialPost): FeedIngestResult {
        val cached = subscribedPrefixes.any { post.geohash.startsWith(it) }
        if (cached) {
            posts[post.postId] = post
        }
        return FeedIngestResult(shouldRelay = true, cached = cached)
    }

    /** Queries cached posts under [prefix], newest first and excluding expired rows. */
    fun query(prefix: String, now: Instant): List<SocialPost> =
        posts.values
            .filter { it.geohash.startsWith(prefix) && it.expiresAt.isAfter(now) }
            .sortedByDescending { it.createdAt }

    internal fun contains(postId: String): Boolean = postId in posts

    internal fun available(prefixes: Set<String>, now: Instant): List<SocialPost> =
        posts.values.filter { post ->
            post.expiresAt.isAfter(now) && prefixes.any { post.geohash.startsWith(it) }
        }
}

/** Result of applying a post to the local feed cache. */
data class FeedIngestResult(
    /** True when packet should continue TTL-bounded relay. */
    val shouldRelay: Boolean,
    /** True when the post was cached locally. */
    val cached: Boolean,
)

/** Store-and-forward cache for post partition-heal sync. */
class StoreForwardCache(subscribedPrefixes: Set<String>) {
    /** Feed view over cached posts. */
    val feed: FeedStore = FeedStore(subscribedPrefixes)

    /** Applies one post to cache. */
    fun ingest(post: SocialPost): FeedIngestResult = feed.ingest(post)

    /** Applies multiple posts to cache. */
    fun ingest(posts: List<SocialPost>): List<FeedIngestResult> = posts.map(::ingest)

    /** Builds a digest of available post ids for matching prefixes. */
    fun digestFor(prefixes: Set<String>, now: Instant): SyncDigest =
        SyncDigest(feed.available(prefixes, now).mapTo(linkedSetOf()) { it.postId })

    /** Returns ids in [digest] missing locally. */
    fun wantedFrom(digest: SyncDigest): Set<String> =
        digest.postIds.filterNot(feed::contains).toSet()

    /** Pulls posts by id. */
    fun pull(ids: Set<String>): List<SocialPost> =
        feed.available(prefixes = setOf(""), now = Instant.MIN).filter { it.postId in ids }
}

/** Summary of post ids offered to a newly connected peer. */
data class SyncDigest(
    /** Offered post ids. */
    val postIds: Set<String>,
)
