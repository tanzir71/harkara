package io.github.tanzir71.harkara.feed

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.feed.ComposeVerdict
import io.github.tanzir71.harkara.core.feed.ContentFilter
import io.github.tanzir71.harkara.core.feed.FeedPost
import io.github.tanzir71.harkara.core.feed.ModerationLadder
import io.github.tanzir71.harkara.core.feed.ModerationState
import io.github.tanzir71.harkara.core.feed.PostComposer
import io.github.tanzir71.harkara.core.feed.Reactions
import io.github.tanzir71.harkara.core.feed.ReportReason
import io.github.tanzir71.harkara.core.feed.WordFilters
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Nip13
import io.github.tanzir71.harkara.core.relay.Filter
import io.github.tanzir71.harkara.core.relay.Negentropy
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.MuteEntity
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One feed card's worth of data. */
data class FeedItem(
    val post: FeedPost,
    val moderation: ModerationState,
    val distanceMeters: Double,
    val own: Boolean,
    val carrier: Carrier,
)

/** Result of trying to post. */
data class PostResult(val verdict: ComposeVerdict, val event: Event? = null)

/**
 * The Nearby feed: posts within 3 km of the chosen area, from Room (so it works offline), moderated
 * locally, plus posting, votes and reports signed with the weekly area key.
 */
class FeedRepository(
    private val db: HarkaraDatabase,
    private val store: RoomEventStore,
    private val outbox: Outbox,
    private val pool: RelayPool,
    private val wordFilters: WordFilters,
    private val areaKey: (geohash5: String) -> KeyPair,
    private val powBits: () -> Int = { PostComposer.MIN_POW_BITS },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val ladder = ModerationLadder(wordFilters.moderation)

    /** Feed radius around the area centre. */
    val radiusMeters = 3_000.0

    @OptIn(ExperimentalCoroutinesApi::class)
    fun feed(center: LatLng): Flow<List<FeedItem>> {
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        val mutes = db.muteDao().observeAll()
        return db.eventDao().observeFeed(cells, nowMillis() / 1000).flatMapLatest { rows ->
            val ids = rows.map { it.id }
            val reactions = if (ids.isEmpty()) flowOf(emptyList()) else db.eventDao().observeReactions(ids)
            combine(reactions, mutes) { reactionRows, muteRows ->
                val blocked = muteRows.filter { it.type == MuteEntity.AUTHOR }.map { it.value }.toSet()
                val filter = ContentFilter(wordFilters, muteRows.filter { it.type == MuteEntity.WORD }.map { it.value }.toSet())
                val reactionEvents = reactionRows.map { Event.fromJson(it.json) }
                val me = reactionRows.filter { it.own }.map { it.pubkey }.toSet() + rows.filter { it.own }.map { it.pubkey }
                val now = nowMillis() / 1000
                rows.mapNotNull { row ->
                    val post = FeedPost.from(Event.fromJson(row.json)) ?: return@mapNotNull null
                    if (post.isExpired(now)) return@mapNotNull null
                    val distance = GeoMath.distanceMeters(center, post.location)
                    if (distance > radiusMeters) return@mapNotNull null
                    val state = ladder.evaluate(post.event, reactionEvents, blocked, filter, me)
                    FeedItem(post, state, distance, row.own, Carrier.valueOf(row.carrier))
                }.filterNot { it.moderation.hidden }
            }
        }
    }

    private fun areaFilter(center: LatLng): Filter {
        val cells = GeoMath.withNeighbours(GeoHash.encode(center.lat, center.lng, 5))
        val since = nowMillis() / 1000 - 2 * 24 * 3600
        return Filter(kinds = listOf(Kinds.FEED_POST), tags = mapOf("g" to cells), since = since, limit = 300)
    }

    /** Subscribes the relays to posts in the area's cell and its neighbours. */
    fun subscribeArea(center: LatLng) {
        pool.subscribe(SUB_FEED, listOf(areaFilter(center)))
    }

    /** NIP-77: on relays that offer negentropy, fetch only the area posts we don't already hold. */
    suspend fun catchUp(center: LatLng): Int {
        val filter = areaFilter(center)
        val local = db.eventDao().feedIdTimes(filter.tags.getValue("g"), filter.since ?: 0)
            .map { Negentropy.Item.of(it.createdAt, it.id) }
        return pool.reconcile(SUB_FEED, filter.copy(limit = null), local)
    }

    /** Follows votes and reports on the posts currently shown. */
    fun subscribeReactions(postIds: List<String>) {
        if (postIds.isEmpty()) return
        pool.subscribe(SUB_REACTIONS, listOf(Filter(kinds = listOf(Kinds.REACTION, Kinds.REPORT), tags = mapOf("e" to postIds.take(200)))))
    }

    /** Stores what relays send us. Feed posts need PoW, a geohash, and must respect the per-key rate limit. */
    fun startIngest(scope: CoroutineScope) {
        scope.launch {
            pool.incoming.collect { incoming -> ingest(incoming.event, Carrier.ONLINE) }
        }
    }

    suspend fun ingest(event: Event, carrier: Carrier): Boolean {
        if (event.kind == Kinds.FEED_POST) {
            if (FeedPost.from(event) == null) return false
            if (Nip13.difficulty(event.id) < PostComposer.MIN_POW_BITS) return false
            if (!withinRateLimit(event.pubkey, event.createdAt)) return false
        }
        if (event.kind !in INGESTED_KINDS) return false
        return store.store(event, carrier, own = false)
    }

    /** 1 post a minute, 10 an hour, 30 a day per key (handover §4). */
    private suspend fun withinRateLimit(pubkey: String, at: Long): Boolean {
        val dao = db.eventDao()
        return dao.countSince(pubkey, Kinds.FEED_POST, at - 60) < 1 &&
            dao.countSince(pubkey, Kinds.FEED_POST, at - 3600) < 10 &&
            dao.countSince(pubkey, Kinds.FEED_POST, at - 86_400) < 30
    }

    /** Our own posting limit, the same numbers peers enforce. */
    suspend fun canPostNow(): Boolean {
        val now = nowMillis() / 1000
        val dao = db.eventDao()
        return dao.countOwnSince(Kinds.FEED_POST, now - 60) < 1 &&
            dao.countOwnSince(Kinds.FEED_POST, now - 3600) < 10 &&
            dao.countOwnSince(Kinds.FEED_POST, now - 86_400) < 30
    }

    suspend fun post(text: String, category: Category?, details: Map<String, String>, at: LatLng): PostResult {
        val verdict = ContentFilter(wordFilters).checkDraft(text, category)
        if (verdict != ComposeVerdict.OK) return PostResult(verdict)
        if (!canPostNow()) return PostResult(ComposeVerdict.RATE_LIMITED)
        val g5 = GeoHash.encode(at.lat, at.lng, 5)
        val event = withContext(Dispatchers.Default) {
            PostComposer.compose(areaKey(g5), text, category, at.lat, at.lng, nowMillis() / 1000, details, powBits())
        }
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, Priority.POST)
        return PostResult(ComposeVerdict.OK, event)
    }

    suspend fun vote(post: FeedPost, up: Boolean) {
        val event = Reactions.vote(areaKey(post.geohash.take(5)), post.event, up, nowMillis() / 1000)
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, Priority.REACTION)
    }

    suspend fun report(post: FeedPost, reason: ReportReason) {
        val event = Reactions.report(areaKey(post.geohash.take(5)), post.event, reason, nowMillis() / 1000)
        store.store(event, Carrier.SAVED, own = true)
        outbox.enqueue(event, Priority.REACTION)
    }

    suspend fun blockAuthor(pubkey: String) = db.muteDao().add(MuteEntity(MuteEntity.AUTHOR, pubkey, nowMillis()))

    suspend fun muteWord(word: String) = db.muteDao().add(MuteEntity(MuteEntity.WORD, word.trim().lowercase(), nowMillis()))

    companion object {
        const val SUB_FEED = "harkara-feed"
        const val SUB_REACTIONS = "harkara-reactions"
        private val INGESTED_KINDS = setOf(Kinds.FEED_POST, Kinds.REACTION, Kinds.REPORT)

        fun Flow<List<FeedItem>>.ids(): Flow<List<String>> = map { list -> list.map { it.post.id } }
    }
}
