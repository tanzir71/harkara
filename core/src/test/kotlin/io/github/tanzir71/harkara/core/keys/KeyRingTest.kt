package io.github.tanzir71.harkara.core.keys

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Hex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class KeyRingTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val ring = KeyRing(seed)

    /** Expected values computed independently with Python's hmac/hashlib (RFC 5869, empty salt). */
    @Test
    fun `derivation matches independent HKDF-SHA256 with the documented labels`() {
        assertEquals("bf9aa4d944970ad1797e000f72c8e8da4b2e44c0944f2edda113cad0d9dc24af", Hex.encode(ring.profile().privateKey))
        assertEquals("d55e529c101c35b09bce779c40375dccd1d42a7423b65ca5825575ed281e4bb3", Hex.encode(ring.area("wh0r3", "2026-W48").privateKey))
        assertEquals("392d3e8bf3382850dc3ec963b4ffe9f7088a46d98bec26840bdf16475e3c3035", Hex.encode(ring.session("share-1").privateKey))
        assertEquals("2175132744ca6e2fa23fca6b796c9589", Hex.encode(ring.meshPresenceId(LocalDate.of(2026, 11, 25))))
    }

    @Test
    fun `keys are deterministic per seed and distinct per purpose`() {
        val again = KeyRing(seed.copyOf())
        assertEquals(ring.profile(), again.profile())
        assertEquals(ring.area("wh0r0", "2026-W48"), again.area("wh0r0", "2026-W48"))

        val keys = listOf(
            ring.profile(),
            ring.area("wh0r0", "2026-W48"),
            ring.area("wh0r0", "2026-W49"),
            ring.area("wh0r3", "2026-W48"),
            ring.session("a"),
            ring.session("b"),
        ).map { it.pubkeyHex }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(KeyRing(ByteArray(32) { 1 }).profile(), ring.profile())
    }

    @Test
    fun `mesh presence id rotates daily and is 16 bytes`() {
        val monday = ring.meshPresenceId(LocalDate.of(2026, 11, 23))
        val tuesday = ring.meshPresenceId(LocalDate.of(2026, 11, 24))
        assertEquals(16, monday.size)
        assertFalse(monday.contentEquals(tuesday))
        assertArrayEquals(monday, KeyRing(seed).meshPresenceId(LocalDate.of(2026, 11, 23)))
        assertFalse(monday.contentEquals(KeyRing(ByteArray(32) { 9 }).meshPresenceId(LocalDate.of(2026, 11, 23))))
    }

    @Test
    fun `area key helper uses geohash-5 and the ISO week`() {
        val date = LocalDate.of(2026, 11, 25)
        assertEquals("wh0r0", GeoHash.area5(23.7461, 90.3742))
        assertEquals(ring.area("wh0r0", "2026-W48"), ring.areaAt(23.7461, 90.3742, date))
        assertEquals(ring.area("wh0r3", "2026-W48"), ring.areaAt(23.7937, 90.4066, date))
    }

    @Test
    fun `input checks`() {
        assertThrows(IllegalArgumentException::class.java) { KeyRing(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { ring.area("wh0r", "2026-W48") }
        assertThrows(IllegalArgumentException::class.java) { ring.area("wh0ra", "2026-W48") }
        assertThrows(IllegalArgumentException::class.java) { ring.area("wh0r0", "2026-48") }
        assertThrows(IllegalArgumentException::class.java) { ring.session("") }
        assertEquals(32, KeyRing.generateSeed().size)
    }

    @Test
    fun `iso weeks`() {
        assertEquals("2026-W48", IsoWeek.of(LocalDate.of(2026, 11, 25)))
        assertEquals("2026-W53", IsoWeek.of(LocalDate.of(2027, 1, 1)))
        assertEquals("2020-W53", IsoWeek.of(LocalDate.of(2021, 1, 3)))
        assertEquals("2026-W01", IsoWeek.of(LocalDate.of(2025, 12, 29)))
        assertTrue(IsoWeek.isValid("2026-W53"))
        assertFalse(IsoWeek.isValid("2026-W54"))
        // Sunday 23:30 UTC is already Monday in Dhaka (UTC+6): the week turns over on Dhaka time.
        val clock = Clock.fixed(Instant.parse("2026-11-29T18:30:00Z"), ZoneOffset.UTC)
        assertEquals("2026-W49", IsoWeek.current(clock.withZone(IsoWeek.DHAKA)))
    }
}
