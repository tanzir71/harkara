package io.github.tanzir71.harkara.core

import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.tools.SosEvents
import java.time.Duration
import java.time.Instant

/**
 * Local SOS acceptance: muted keys are dropped, a key may raise one SOS per [cooldown] (30 min), and a
 * `resolved`/`false_alarm` [Kinds.SOS_UPDATE] clears the active SOS so a genuine new one is accepted.
 */
class SosPolicy(
    private val cooldown: Duration = Duration.ofMinutes(30),
    private val mutedPubkeys: Set<String> = emptySet(),
) {
    private val active = mutableMapOf<String, Event>()

    /** True when an SOS or SOS update may be shown and relayed. Other kinds pass untouched. */
    fun accept(event: Event): Boolean {
        if (event.kind != Kinds.SOS && event.kind != Kinds.SOS_UPDATE) return true
        if (event.pubkey in mutedPubkeys || !event.verify()) return false
        return when (event.kind) {
            Kinds.SOS_UPDATE -> {
                if (!SosEvents.isClosingUpdate(event)) return false
                // Replaying an old resolution must not reset a later emergency's cooldown.
                if (active[event.pubkey]?.let { SosEvents.closes(event, it) } == true) active.remove(event.pubkey)
                true
            }
            else -> {
                val previous = active[event.pubkey]
                if (previous != null && event.createdAt - previous.createdAt < cooldown.seconds) {
                    false
                } else {
                    active[event.pubkey] = event
                    true
                }
            }
        }
    }

    /** True while [pubkey] has an SOS that has not been resolved. */
    fun isActive(pubkey: String): Boolean = pubkey in active
}

/** Mesh duty modes. */
enum class MeshDuty {
    LOW_POWER,
    BALANCED,
    MAXIMUM,
}

/** Disaster-mode derived settings. */
class DisasterModePolicy(private val enabled: Boolean) {
    /** True when the UI should surface SOS. */
    val showSosTab: Boolean = enabled

    /** Feed prefix length under this policy (wider area in a disaster). */
    fun feedPrefixLength(normalPrefixLength: Int): Int =
        if (enabled) minOf(normalPrefixLength, 4) else normalPrefixLength

    fun meshDuty(normal: MeshDuty): MeshDuty = if (enabled) MeshDuty.MAXIMUM else normal
}

/** Battery-aware mesh duty selection. */
object BatteryAdaptiveDutyPolicy {
    fun dutyFor(batteryPercent: Int, charging: Boolean, disasterMode: Boolean): MeshDuty = when {
        disasterMode -> MeshDuty.MAXIMUM
        !charging && batteryPercent <= 20 -> MeshDuty.LOW_POWER
        else -> MeshDuty.BALANCED
    }
}

/** Retention cutoffs for local data. */
class RetentionPolicy {
    /** True when [event] has passed its NIP-40 expiration. */
    fun shouldPurge(event: Event, now: Instant): Boolean = event.isExpired(now.epochSecond)

    /** Dedupe rows older than this cutoff can be purged. */
    fun dedupeCutoff(now: Instant): Instant = now.minus(DEDUPE_RETENTION)

    companion object {
        val DEDUPE_RETENTION: Duration = Duration.ofMinutes(30)
    }
}
