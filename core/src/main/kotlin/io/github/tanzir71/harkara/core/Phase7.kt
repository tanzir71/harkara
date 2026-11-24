package io.github.tanzir71.harkara.core

import java.time.Duration
import java.time.Instant

/** SOS event kind. */
enum class SosKind {
    SEND,
    RESOLVE,
}

/** Verified SOS event. */
data class SosEvent(
    /** Sender identity. */
    val sender: NearId,
    /** SOS event kind. */
    val kind: SosKind,
    /** Event time. */
    val at: Instant,
)

/** Local SOS acceptance, mute, and rate-limit policy. */
class SosPolicy(
    private val cooldown: Duration,
    private val mutedSenders: Set<NearId> = emptySet(),
) {
    private val active = mutableMapOf<NearId, Instant>()

    /** Returns true when an SOS event may be relayed/handled. */
    fun accept(event: SosEvent): Boolean {
        if (event.sender in mutedSenders) return false
        return when (event.kind) {
            SosKind.RESOLVE -> {
                active.remove(event.sender)
                true
            }
            SosKind.SEND -> {
                val previous = active[event.sender]
                if (previous != null && Duration.between(previous, event.at) < cooldown) {
                    false
                } else {
                    active[event.sender] = event.at
                    true
                }
            }
        }
    }
}

/** Mesh duty modes. */
enum class MeshDuty {
    LOW_POWER,
    BALANCED,
    MAXIMUM,
}

/** Disaster-mode derived settings. */
class DisasterModePolicy(private val enabled: Boolean) {
    /** True when the UI should surface the SOS tab. */
    val showSosTab: Boolean = enabled

    /** Returns the feed prefix length to use under this policy. */
    fun feedPrefixLength(normalPrefixLength: Int): Int =
        if (enabled) minOf(normalPrefixLength, 4) else normalPrefixLength

    /** Returns the mesh duty mode under this policy. */
    fun meshDuty(normal: MeshDuty): MeshDuty = if (enabled) MeshDuty.MAXIMUM else normal
}

/** Feed ordering helpers for bulletins and events. */
object FeedOrdering {
    /** Sorts feed rows with bulletins pinned first, then newest first. */
    fun sortForFeed(posts: List<SocialPost>, now: Instant): List<SocialPost> =
        posts.filter { it.expiresAt.isAfter(now) }
            .sortedWith(
                compareByDescending<SocialPost> { it.kind == PostKind.BULLETIN }
                    .thenByDescending { it.kind == PostKind.EVENT }
                    .thenBy { it.eventStartTime ?: Instant.MAX }
                    .thenByDescending { it.createdAt },
            )

    /** Returns upcoming event posts sorted by start time. */
    fun upcomingEvents(posts: List<SocialPost>, now: Instant): List<SocialPost> =
        posts.filter {
            it.kind == PostKind.EVENT &&
                (it.eventStartTime?.isAfter(now) == true || it.eventStartTime?.equals(now) == true)
        }
            .sortedBy { it.eventStartTime }
}
