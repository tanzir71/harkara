package io.github.tanzir71.harkara.core

import java.time.Duration
import java.time.Instant

/** Battery-aware mesh duty selection. */
object BatteryAdaptiveDutyPolicy {
    /** Chooses mesh duty from current battery state. */
    fun dutyFor(batteryPercent: Int, charging: Boolean, disasterMode: Boolean): MeshDuty = when {
        disasterMode -> MeshDuty.MAXIMUM
        !charging && batteryPercent <= 20 -> MeshDuty.LOW_POWER
        else -> MeshDuty.BALANCED
    }
}

/** Retention cutoffs for ephemeral local data. */
class RetentionPolicy {
    /** Returns true when [post] should be purged. */
    fun shouldPurgePost(post: SocialPost, now: Instant): Boolean = !post.expiresAt.isAfter(now)

    /** Dedupe rows older than this cutoff should be purged. */
    fun dedupeCutoff(now: Instant): Instant = now.minus(DEDUPE_RETENTION)

    companion object {
        /** Retention duration for dedupe entries. */
        val DEDUPE_RETENTION: Duration = Duration.ofMinutes(30)
    }
}
