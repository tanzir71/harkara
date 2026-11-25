package io.github.tanzir71.harkara.core.store

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds

/** Result of offering an event to an [EventStore]. */
data class StoreResult(
    /** True when the event should keep travelling (TTL permitting), whether or not it was kept here. */
    val shouldRelay: Boolean,
    /** True when the event was kept for store-and-forward. */
    val cached: Boolean,
)

/** Event ids a peer holds, offered on connect so the other side can pull what it lacks. */
data class SyncDigest(val eventIds: Set<String>)

/**
 * In-memory store-and-forward cache for mesh and bridge sync. Keeps events whose `g` tag falls under a
 * subscribed geohash prefix, plus gift wraps carried for others; relays everything else without keeping it.
 * Oldest events are evicted first beyond [capacity]. (The Room-backed store with the 20 MB budget is M4.)
 */
class EventStore(
    private val subscribedPrefixes: Set<String>,
    private val carryGiftWraps: Boolean = true,
    private val capacity: Int = 5_000,
    private val maxBytes: Long = 20_000_000,
) {
    private val events = LinkedHashMap<String, Event>()
    private val sizes = HashMap<String, Int>()
    private var bytes = 0L

    val size: Int
        get() = events.size

    /** Offers a verified event. Expired events are neither kept nor relayed. */
    fun ingest(event: Event, nowSeconds: Long): StoreResult {
        if (event.isExpired(nowSeconds)) return StoreResult(shouldRelay = false, cached = false)
        val keep = matchesArea(event) || event.kind in REFERENCED_KINDS ||
            (carryGiftWraps && (event.kind == Kinds.GIFT_WRAP))
        if (keep) {
            val size = event.toJson().encodeToByteArray().size
            bytes += size - (sizes.put(event.id, size) ?: 0)
            events[event.id] = event
            evict()
        }
        return StoreResult(shouldRelay = true, cached = event.id in events)
    }

    fun ingestAll(events: List<Event>, nowSeconds: Long): List<StoreResult> = events.map { ingest(it, nowSeconds) }

    operator fun contains(id: String): Boolean = id in events

    fun get(id: String): Event? = events[id]

    /** Kept, unexpired events under [prefix] (by `g` tag), newest first; optionally only [kinds]. */
    fun query(prefix: String, nowSeconds: Long, kinds: Set<Int>? = null): List<Event> =
        events.values
            .filter { !it.isExpired(nowSeconds) && it.tagValues("g").any { g -> g.startsWith(prefix) } && (kinds == null || it.kind in kinds) }
            .sortedByDescending { it.createdAt }

    /** Ids of every kept, unexpired event. */
    fun digest(nowSeconds: Long): SyncDigest =
        SyncDigest(events.values.filterNot { it.isExpired(nowSeconds) }.mapTo(linkedSetOf()) { it.id })

    /** Ids in [digest] this store lacks. */
    fun wantedFrom(digest: SyncDigest): Set<String> = digest.eventIds.filterNot(events::containsKey).toSet()

    /** Kept events with the given ids. */
    fun pull(ids: Collection<String>): List<Event> = ids.mapNotNull(events::get)

    fun purgeExpired(nowSeconds: Long): Int {
        val before = events.size
        events.values.filter { it.isExpired(nowSeconds) }.map { it.id }.forEach(::remove)
        return before - events.size
    }

    private fun matchesArea(event: Event): Boolean {
        val areas = event.tagValues("g")
        return areas.any { g -> subscribedPrefixes.any { prefix -> g.startsWith(prefix) } }
    }

    private fun evict() {
        if (events.size <= capacity && bytes <= maxBytes) return
        for (event in events.values.sortedBy { it.createdAt }) {
            if (events.size <= capacity && bytes <= maxBytes) break
            remove(event.id)
        }
    }

    private fun remove(id: String) {
        events.remove(id)
        bytes -= sizes.remove(id) ?: 0
    }

    private companion object {
        // Follow-ups have only parent-event/address tags, but must survive a partition too.
        val REFERENCED_KINDS = setOf(Kinds.TASK_STEP, Kinds.REACTION, Kinds.REPORT, Kinds.SOS_UPDATE, Kinds.SMS_DM)
    }
}
