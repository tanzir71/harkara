package io.github.tanzir71.harkara.core.tools

import io.github.tanzir71.harkara.core.SosPolicy
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class ToolsTest {
    private val dhaka = 23.8103 to 90.4125

    @Test
    fun `prayer times for Dhaka follow Karachi with Hanafi asr`() {
        val day = PrayerTimesCalc.forDay(dhaka.first, dhaka.second, LocalDate.of(2026, 11, 25))
        val list = day.all().map { it.second }
        assertEquals(list.sorted(), list)
        val local = day.fajr.atZone(ZoneId.of("Asia/Dhaka")).toLocalTime()
        assertTrue(local.hour == 4 || local.hour == 5, "fajr at $local")
        val maghrib = day.maghrib.atZone(ZoneId.of("Asia/Dhaka")).toLocalTime()
        assertTrue(maghrib.hour == 17, "maghrib at $maghrib")
        val shafi = PrayerTimesCalc.forDay(dhaka.first, dhaka.second, LocalDate.of(2026, 11, 25), hanafi = false)
        assertTrue(Duration.between(shafi.asr, day.asr).toMinutes() in 20..90)
        assertEquals("dhuhr", day.next(day.sunrise.plusSeconds(60))?.first)
        assertNull(day.next(day.isha.plusSeconds(60)))
    }

    @Test
    fun `qibla from Dhaka points west by north-west`() {
        val q = PrayerTimesCalc.qibla(dhaka.first, dhaka.second)
        assertTrue(q in 275.0..281.0, "qibla $q")
    }

    @Test
    fun `morse encodes and times SOS`() {
        assertEquals("... --- ...", Morse.encode("sos"))
        assertEquals("... --- ... / .... .-", Morse.encode("SOS HA"))
        val t = Morse.timings("SOS", 100)
        // ... = 3 dots + 2 gaps, letter gap, --- = 3 dashes + 2 gaps, letter gap, ... again
        assertEquals(listOf(100L, 100, 100, 100, 100, 300, 300, 100, 300, 100, 300, 300, 100, 100, 100, 100, 100), t)
        assertEquals(t + 700L, Morse.sos(100))
    }

    @Test
    fun `sos is rate limited to one per half hour per key and resolves`() {
        val keys = KeyPair.generate()
        val now = 1_764_000_000L
        val first = SosEvents.sos(keys, dhaka.first, dhaka.second, "Water rising at Road 7", now, "Tania")
        assertEquals(Kinds.SOS, first.kind)
        assertTrue(first.verify())
        val policy = SosPolicy()
        assertTrue(policy.accept(first))
        assertFalse(policy.accept(SosEvents.sos(keys, null, null, "again", now + 60)))
        assertEquals(29 * 60L, SosEvents.waitSeconds(now, now + 60, resolvedSince = false))
        val resolved = SosEvents.update(keys, first, SosStatus.RESOLVED, now + 120)
        assertTrue(policy.accept(resolved))
        assertEquals(0, SosEvents.waitSeconds(now, now + 180, resolvedSince = true))
        assertTrue(policy.accept(SosEvents.sos(keys, null, null, "new emergency", now + 180)))
        assertNull(SosEvents.active(listOf(first, resolved), keys.pubkeyHex))
        assertEquals(first.id, SosEvents.active(listOf(first), keys.pubkeyHex)?.id)
        assertThrows(IllegalArgumentException::class.java) { SosEvents.update(KeyPair.generate(), first, SosStatus.FALSE_ALARM, now) }
    }
}
