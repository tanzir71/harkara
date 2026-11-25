package io.github.tanzir71.harkara.core.feed

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip13
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import kotlin.math.floor
import kotlin.math.ln

/** Optional structured details on a post (from loka's composer chips). Tag names are stable wire values. */
object DetailTags {
    const val STOCK_ITEM = "stock"
    const val STOCK_STATUS = "status"
    const val TRAFFIC = "traffic"
    const val CROWD = "crowd"
    const val ALERT = "alert"
    const val PRICE = "price"
    val ALL = listOf(STOCK_ITEM, STOCK_STATUS, TRAFFIC, CROWD, ALERT, PRICE)
}

/** A Nearby feed post read from a kind-1 event. */
data class FeedPost(
    val event: Event,
    val category: Category?,
    /** Most precise `g` tag on the post. */
    val geohash: String,
    val details: Map<String, String>,
) {
    val id: String get() = event.id
    val text: String get() = event.content
    val createdAt: Long get() = event.createdAt
    val expiresAt: Long? get() = event.expiration
    val location: LatLng get() = GeoMath.decode(geohash)

    fun isExpired(nowSeconds: Long): Boolean = event.isExpired(nowSeconds)

    companion object {
        /** Null when the event is not a geotagged feed post. */
        fun from(event: Event): FeedPost? {
            if (event.kind != Kinds.FEED_POST) return null
            val geohash = event.tagValues("g").filter(GeoHash::isValid).maxByOrNull { it.length } ?: return null
            return FeedPost(
                event = event,
                category = event.tagValue("t")?.let(Category::fromTag),
                geohash = geohash,
                details = DetailTags.ALL.mapNotNull { name -> event.tagValue(name)?.let { name to it } }.toMap(),
            )
        }
    }
}

/** Why a draft can't be posted (loka composer rules), or [OK]. */
enum class ComposeVerdict { OK, EMPTY, TOO_LONG, BLOCKED_WORDS, SPAM, TOO_MANY_LINKS, PHONE_NUMBER, RATE_LIMITED }

/** Builds signed, PoW-stamped, expiring feed posts. */
object PostComposer {
    /** Peers drop posts below this NIP-13 difficulty before relaying. */
    const val MIN_POW_BITS = 16
    const val MAX_POW_BITS = 20
    /** Precisions published as `g` tags so relays can match any prefix length we subscribe to. */
    val GEOHASH_PRECISIONS = listOf(7, 6, 5)

    /**
     * Picks the difficulty that keeps mining near [targetSeconds] on this phone, between 16 and 20 bits.
     * Expected work for b bits is 2^b hashes.
     */
    fun tunePowBits(hashesPerSecond: Double, targetSeconds: Double = 2.0): Int {
        if (hashesPerSecond <= 0) return MIN_POW_BITS
        val bits = floor(ln(hashesPerSecond * targetSeconds) / ln(2.0)).toInt()
        return bits.coerceIn(MIN_POW_BITS, MAX_POW_BITS)
    }

    /** Expiration (unix seconds) for a category; Food never expires, untagged posts count as Chatter. */
    fun expirationFor(category: Category?, createdAt: Long): Long? =
        (category ?: Category.CHATTER).expiry?.let { createdAt + it.seconds }

    fun tags(category: Category?, latitude: Double, longitude: Double, createdAt: Long, details: Map<String, String> = emptyMap()): List<List<String>> = buildList {
        GEOHASH_PRECISIONS.forEach { add(listOf("g", GeoHash.encode(latitude, longitude, it))) }
        add(listOf("t", (category ?: Category.CHATTER).tag))
        expirationFor(category, createdAt)?.let { add(listOf("expiration", it.toString())) }
        details.filterKeys { it in DetailTags.ALL }.forEach { (k, v) -> add(listOf(k, v)) }
    }

    /** Signs a post with the weekly [areaKey] after mining [powBits] of proof of work. */
    fun compose(
        areaKey: KeyPair,
        text: String,
        category: Category?,
        latitude: Double,
        longitude: Double,
        createdAt: Long,
        details: Map<String, String> = emptyMap(),
        powBits: Int = MIN_POW_BITS,
    ): Event {
        val template = UnsignedEvent(areaKey.pubkeyHex, createdAt, Kinds.FEED_POST, tags(category, latitude, longitude, createdAt, details), text.trim())
        return Nip13.mine(template, powBits).sign(areaKey)
    }
}
