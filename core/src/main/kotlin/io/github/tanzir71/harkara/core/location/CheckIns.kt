package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.Rumor
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import kotlinx.serialization.Serializable

/** What a check-in says. */
enum class CheckInStatus(val wire: String) {
    /** "I got here safe". */
    SAFE("safe"),
    /** A trip plan: destination and expected arrival, so the people watching know. */
    TRIP("trip"),
    /** Sent by the traveller's phone when the expected arrival passed with no check-in. */
    OVERDUE("overdue"),
    /** The traveller cancelled the trip. */
    CANCELLED("cancelled");

    companion object {
        fun of(wire: String?): CheckInStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** A check-in as read from an opened gift wrap. [id] is the rumor id; [author] is authenticated by the seal. */
data class CheckIn(
    val id: String,
    val author: String,
    val status: CheckInStatus,
    val place: String,
    val geohash: String?,
    val tripId: String?,
    val destination: String?,
    val due: Long?,
    val note: String,
    val createdAt: Long,
    val recipients: List<String>,
    /** The display name the sender chose, if any (never a phone number). */
    val name: String? = null,
)

/**
 * Check-ins (kind 3476, docs/PROTOCOL.md §17): a short rumor by the profile key, sealed (so signed) and
 * gift-wrapped to the chosen people and to the sender; carried at top priority like SOS. Never public:
 * where someone is right now is nobody else's business. Tags: `["status", safe|trip|overdue|cancelled]`,
 * `["place", label]`, optional `["g", geohash-5]` (coarse, never the exact spot), `["trip", id]`,
 * `["dest", label]`, `["due", unix]`, `p` per person; content = an optional short note.
 */
object CheckIns {
    const val MAX_PLACE = 60
    const val MAX_NOTE = 140

    fun rumor(
        profile: KeyPair,
        status: CheckInStatus,
        place: String,
        recipients: List<String>,
        createdAt: Long,
        geohash5: String? = null,
        tripId: String? = null,
        destination: String? = null,
        due: Long? = null,
        note: String = "",
        name: String? = null,
    ): Rumor {
        require(recipients.isNotEmpty()) { "a check-in goes to at least one person" }
        val tags = buildList {
            add(listOf("status", status.wire))
            add(listOf("place", place.trim().take(MAX_PLACE)))
            name?.takeIf { it.isNotBlank() }?.let { add(listOf("name", it.trim().take(40))) }
            geohash5?.takeIf { GeoHash.isValid(it) }?.let { add(listOf("g", it.take(5))) }
            tripId?.let { add(listOf("trip", it)) }
            destination?.let { add(listOf("dest", it.trim().take(MAX_PLACE))) }
            due?.let { add(listOf("due", it.toString())) }
            recipients.distinct().filter { it != profile.pubkeyHex }.forEach { add(listOf("p", it)) }
        }
        return UnsignedEvent(profile.pubkeyHex, createdAt, Kinds.CHECK_IN, tags, note.trim().take(MAX_NOTE))
    }

    /** One wrap per person plus one to the sender (so their other phone and a restore see it). */
    fun wraps(profile: KeyPair, rumor: Rumor): List<Event> = GiftWrap.wrapForGroup(rumor, profile, rumor.tagValues("p"))

    fun parse(rumor: Rumor): CheckIn? {
        if (rumor.kind != Kinds.CHECK_IN) return null
        val status = CheckInStatus.of(rumor.tagValue("status")) ?: return null
        val place = rumor.tagValue("place") ?: return null
        val due = rumor.tagValue("due")?.toLongOrNull()
        if ((status == CheckInStatus.TRIP || status == CheckInStatus.OVERDUE) && (due == null || rumor.tagValue("trip") == null)) return null
        return CheckIn(
            id = rumor.id,
            author = rumor.pubkey,
            status = status,
            place = place.take(MAX_PLACE),
            geohash = rumor.tagValue("g")?.takeIf { GeoHash.isValid(it) }?.take(5),
            tripId = rumor.tagValue("trip"),
            destination = rumor.tagValue("dest")?.take(MAX_PLACE),
            due = due,
            note = rumor.content.take(MAX_NOTE),
            createdAt = rumor.createdAt,
            recipients = rumor.tagValues("p"),
            name = rumor.tagValue("name")?.take(40),
        )
    }
}

enum class TripStatus { ACTIVE, ARRIVED, OVERDUE, CANCELLED }

/** A trip the user started: who watches, where to, and by when. Kept on the phone (and told to the watchers). */
@Serializable
data class TripPlan(
    val id: String,
    val destination: String,
    val due: Long,
    val watchers: List<String>,
    val startedAt: Long,
    val status: TripStatus = TripStatus.ACTIVE,
)

/** When does a trip turn into an overdue alert. */
object TripRules {
    /**
     * True when [trip] is still active, its expected arrival has passed, and none of the traveller's own
     * [checkIns] since the trip started said "safe" (a check-in tagged for another trip doesn't count).
     */
    fun isOverdue(trip: TripPlan, checkIns: List<CheckIn>, now: Long): Boolean =
        trip.status == TripStatus.ACTIVE && now >= trip.due && !arrived(trip, checkIns)

    fun arrived(trip: TripPlan, checkIns: List<CheckIn>): Boolean = checkIns.any {
        it.status == CheckInStatus.SAFE && it.createdAt >= trip.startedAt && (it.tripId == null || it.tripId == trip.id)
    }

    /** The automatic alert, from the traveller's own key. */
    fun overdueRumor(profile: KeyPair, trip: TripPlan, lastPlace: String, now: Long, geohash5: String? = null, name: String? = null): Rumor =
        CheckIns.rumor(profile, CheckInStatus.OVERDUE, lastPlace, trip.watchers, now, geohash5, trip.id, trip.destination, trip.due, name = name)

    fun startRumor(profile: KeyPair, trip: TripPlan, from: String, name: String? = null): Rumor =
        CheckIns.rumor(profile, CheckInStatus.TRIP, from, trip.watchers, trip.startedAt, null, trip.id, trip.destination, trip.due, name = name)
}
