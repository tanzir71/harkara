package io.github.tanzir71.harkara.core.keys

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Hashes
import io.github.tanzir71.harkara.core.nostr.KeyPair
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Every Harkara key comes from one 32-byte root seed that never leaves the phone:
 *
 * - [profile]: chats, tasks, Khata, shared pins.
 * - [area]: the anonymous Nearby feed key, one per geohash-5 cell per ISO week.
 * - [meshPresenceId]: the 16-byte Bluetooth presence id, new every day, unlinkable without the seed.
 * - [session]: one key per live-location share.
 *
 * Derivation is HKDF-SHA256 with the seed as input key material, an empty salt, and a distinct info label
 * per key (`harkara/v1/profile`, `harkara/v1/area/<g5>/<week>`, `harkara/v1/mesh/<yyyy-mm-dd>`,
 * `harkara/v1/session/<id>`). If the 32 bytes are 0 or not below the curve order, the label gets a `#<n>`
 * counter (n = 1, 2, …) and is expanded again.
 */
class KeyRing(rootSeed: ByteArray) {
    private val prk: ByteArray

    init {
        require(rootSeed.size == SEED_BYTES) { "root seed must be $SEED_BYTES bytes" }
        prk = Hashes.hkdfExtract(ByteArray(0), rootSeed)
    }

    fun profile(): KeyPair = derive("harkara/v1/profile")

    /** Anonymous feed key for [geohash5] (5 geohash characters) in [isoWeek] (`2026-W48`). */
    fun area(geohash5: String, isoWeek: String): KeyPair {
        require(geohash5.length == 5 && GeoHash.isValid(geohash5)) { "area keys are scoped to a 5-character geohash" }
        require(IsoWeek.isValid(isoWeek)) { "ISO week must look like 2026-W48" }
        return derive("harkara/v1/area/$geohash5/$isoWeek")
    }

    /** Area key for a position on a date (the date's ISO week). */
    fun areaAt(latitude: Double, longitude: Double, date: LocalDate): KeyPair =
        area(GeoHash.area5(latitude, longitude), IsoWeek.of(date))

    /** 16-byte presence id for the Bluetooth mesh on [date]; rotates daily. */
    fun meshPresenceId(date: LocalDate): ByteArray =
        Hashes.hkdfExpand(prk, "harkara/v1/mesh/$date".encodeToByteArray(), MESH_ID_BYTES)

    /** Key for one live-location share. */
    fun session(sessionId: String): KeyPair {
        require(sessionId.isNotEmpty() && '/' !in sessionId && '#' !in sessionId) { "session id must be non-empty without '/' or '#'" }
        return derive("harkara/v1/session/$sessionId")
    }

    private fun derive(label: String): KeyPair {
        var counter = 0
        while (true) {
            val info = if (counter == 0) label else "$label#$counter"
            val candidate = Hashes.hkdfExpand(prk, info.encodeToByteArray(), 32)
            if (KeyPair.isValidPrivateKey(candidate)) return KeyPair(candidate).also { candidate.fill(0) }
            counter++
        }
    }

    companion object {
        const val SEED_BYTES: Int = 32
        const val MESH_ID_BYTES: Int = 16

        fun generateSeed(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SEED_BYTES).also(random::nextBytes)
    }
}

/** ISO-8601 week labels such as `2026-W48`, which scope the weekly area key. */
object IsoWeek {
    private val PATTERN = Regex("""\d{4}-W(0[1-9]|[1-4]\d|5[0-3])""")

    fun of(date: LocalDate): String {
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return String.format(Locale.ROOT, "%04d-W%02d", year, week)
    }

    fun current(clock: Clock = Clock.system(DHAKA)): String = of(LocalDate.now(clock))

    fun isValid(value: String): Boolean = PATTERN.matches(value)

    /** Weeks (and mesh days) turn over on Dhaka time. */
    val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
}
