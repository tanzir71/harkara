package io.github.tanzir71.harkara.core

import io.github.tanzir71.harkara.core.TestData.NOW
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PoliciesTest {
    private val person = TestData.keys(4)

    private fun sos(at: Long) = person.signEvent(Kinds.SOS, content = "help", createdAt = NOW + at)
    private fun update(at: Long, status: String, target: String) = person.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", target), listOf("status", status)), "ok", NOW + at)

    @Test
    fun `sos policy rate limits active alerts and allows the resolve flow`() {
        val policy = SosPolicy(cooldown = Duration.ofMinutes(30))

        assertTrue(policy.accept(sos(0)))
        assertTrue(policy.isActive(person.pubkeyHex))
        assertFalse(policy.accept(sos(60)))
        assertTrue(policy.accept(update(120, "resolved", sos(0).id)))
        assertFalse(policy.isActive(person.pubkeyHex))
        assertTrue(policy.accept(sos(180)))
        assertTrue(policy.accept(update(200, "false_alarm", sos(180).id)))
        assertTrue(policy.accept(sos(240)))
        assertFalse(policy.accept(sos(300)))
        assertTrue(policy.accept(sos(240 + 1800)), "after 30 minutes a new SOS is accepted")
        assertTrue(policy.accept(TestData.post(person)), "other kinds pass")
    }

    @Test
    fun `muted sos senders are dropped`() {
        val policy = SosPolicy(mutedPubkeys = setOf(person.pubkeyHex))
        assertFalse(policy.accept(sos(0)))
    }

    @Test
    fun `only the matching signed resolution resets an active alert`() {
        val policy = SosPolicy()
        val first = sos(0)
        val resolved = update(120, "resolved", first.id)
        assertTrue(policy.accept(first))
        assertFalse(policy.accept(update(60, "unknown", first.id)))
        assertTrue(policy.isActive(person.pubkeyHex))
        assertFalse(policy.accept(resolved.copy(content = "forged")))
        assertTrue(policy.accept(resolved))
        assertTrue(policy.accept(sos(180)))
        assertTrue(policy.accept(resolved), "old resolutions may be carried again")
        assertTrue(policy.isActive(person.pubkeyHex), "an old resolution cannot close the new SOS")
        assertFalse(policy.accept(sos(240)), "replaying a resolution cannot bypass cooldown")
        assertTrue(policy.accept(update(150, "resolved", sos(180).id)))
        assertTrue(policy.isActive(person.pubkeyHex), "backdated updates cannot close the SOS")
        assertTrue(policy.accept(TestData.keys(9).signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", sos(180).id), listOf("status", "resolved")), "", NOW + 250)))
        assertTrue(policy.isActive(person.pubkeyHex), "another sender cannot close the SOS")
    }

    @Test
    fun `disaster mode widens the feed and maxes mesh duty`() {
        val policy = DisasterModePolicy(enabled = true)
        assertEquals(4, policy.feedPrefixLength(normalPrefixLength = 6))
        assertEquals(MeshDuty.MAXIMUM, policy.meshDuty(normal = MeshDuty.BALANCED))
        assertTrue(policy.showSosTab)
        assertEquals(6, DisasterModePolicy(enabled = false).feedPrefixLength(6))
    }

    @Test
    fun `battery policy lowers duty on low battery and maxes during disaster`() {
        assertEquals(MeshDuty.LOW_POWER, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 10, charging = false, disasterMode = false))
        assertEquals(MeshDuty.BALANCED, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 80, charging = false, disasterMode = false))
        assertEquals(MeshDuty.MAXIMUM, BatteryAdaptiveDutyPolicy.dutyFor(batteryPercent = 5, charging = false, disasterMode = true))
    }

    @Test
    fun `retention purges expired events and old dedupe rows`() {
        val now = Instant.ofEpochSecond(NOW)
        val policy = RetentionPolicy()
        assertTrue(policy.shouldPurge(TestData.post(person, createdAt = NOW - 7200, expiresAt = NOW - 1), now))
        assertFalse(policy.shouldPurge(TestData.post(person, expiresAt = NOW + 1), now))
        assertFalse(policy.shouldPurge(TestData.post(person, expiresAt = null), now))
        assertEquals(now.minus(RetentionPolicy.DEDUPE_RETENTION), policy.dedupeCutoff(now))
    }

    @Test
    fun `media compressor keeps mesh images under 48 KB`() {
        val compressed = MediaCompressor.compressForMesh(ByteArray(100_000) { it.toByte() })
        assertEquals("image/webp", compressed.mimeType)
        assertTrue(compressed.bytes.size <= 48 * 1024)
        assertTrue(compressed.blurHash.isNotBlank())
    }

    @Test
    fun `geohash buckets`() {
        assertEquals("9q8yyk", GeoHash.encode(latitude = 37.7749, longitude = -122.4194, precision = 6))
        assertEquals("wh0r0d", GeoHash.encode(23.7461, 90.3742))
        assertEquals("wh0r3", GeoHash.area5(23.7937, 90.4066))
        assertTrue(GeoHash.isValid("wh0r3"))
        assertFalse(GeoHash.isValid("wh0ra"))
    }
}
