package io.github.tanzir71.harkara.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class Phase8PolishTest {
    @Test
    fun `battery policy lowers duty on low battery and maxes during disaster`() {
        assertEquals(MeshDuty.LOW_POWER, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 10, charging = false, disasterMode = false))
        assertEquals(MeshDuty.BALANCED, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 80, charging = false, disasterMode = false))
        assertEquals(MeshDuty.MAXIMUM, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 5, charging = false, disasterMode = true))
    }

    @Test
    fun `retention policy purges expired posts and old dedupe`() {
        val now = Instant.ofEpochMilli(10_000)
        val policy = RetentionPolicy()
        val expired = social("expired", expiresAt = now.minusMillis(1))
        val fresh = social("fresh", expiresAt = now.plusMillis(1))

        assertTrue(policy.shouldPurgePost(expired, now))
        assertFalse(policy.shouldPurgePost(fresh, now))
        assertEquals(now.minus(RetentionPolicy.DEDUPE_RETENTION), policy.dedupeCutoff(now))
    }

    private fun social(id: String, expiresAt: Instant): SocialPost =
        SocialPost(
            postId = id,
            author = NearId(ByteArray(16) { 1 }),
            displayName = "Aster",
            text = id,
            geohash = "9q8yyk",
            createdAt = Instant.EPOCH,
            expiresAt = expiresAt,
            signature = ByteArray(64),
        )
}
