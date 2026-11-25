package io.github.tanzir71.harkara.core.nostr

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.GeoHash

/** Builders for the event shapes in docs/PROTOCOL.md, so every screen tags them the same way. */
object EventBuilder {
    /** Default feed-post proof of work (handover: 16-20 bits, tuned for budget phones). */
    const val FEED_POW_BITS: Int = 16

    /**
     * Nearby feed post (kind 1) signed by an [areaKey]: `g` at precisions 6, 5 and 4 (so relays can filter
     * by area), `t` category, NIP-40 `expiration` from the category, then NIP-13 work of [powBits].
     */
    fun feedPost(
        areaKey: KeyPair,
        text: String,
        geohash6: String,
        category: Category,
        createdAt: Long = nowSeconds(),
        powBits: Int = FEED_POW_BITS,
        extraTags: List<List<String>> = emptyList(),
    ): Event {
        require(geohash6.length >= 6 && GeoHash.isValid(geohash6)) { "feed posts carry a geohash of at least 6 characters" }
        val tags = buildList {
            add(listOf("g", geohash6))
            add(listOf("g", geohash6.take(5)))
            add(listOf("g", geohash6.take(4)))
            add(listOf("t", category.tag))
            category.expiry?.let { add(listOf("expiration", (createdAt + it.seconds).toString())) }
            addAll(extraTags)
        }
        val template = UnsignedEvent(areaKey.pubkeyHex, createdAt, Kinds.FEED_POST, tags, text)
        return (if (powBits > 0) Nip13.mine(template, powBits) else template).sign(areaKey)
    }

    /** NIP-25 vote on [target]: `+` or `-`. */
    fun vote(voter: KeyPair, target: Event, up: Boolean, createdAt: Long = nowSeconds()): Event =
        voter.signEvent(
            Kinds.REACTION,
            listOf(listOf("e", target.id), listOf("p", target.pubkey), listOf("k", target.kind.toString())),
            if (up) "+" else "-",
            createdAt,
        )

    /** NIP-56 report of [target] (`spam`, `illegal`, `impersonation`, `profanity`, `nudity`, `malware`, `other`). */
    fun report(reporter: KeyPair, target: Event, type: String, note: String = "", createdAt: Long = nowSeconds()): Event {
        require(type in REPORT_TYPES) { "unknown NIP-56 report type" }
        return reporter.signEvent(
            Kinds.REPORT,
            listOf(listOf("e", target.id, type), listOf("p", target.pubkey)),
            note,
            createdAt,
        )
    }

    val REPORT_TYPES: Set<String> = setOf("nudity", "malware", "profanity", "illegal", "spam", "impersonation", "other")
}
