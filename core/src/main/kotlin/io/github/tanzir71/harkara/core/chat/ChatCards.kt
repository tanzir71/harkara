package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.OpenLocationCode
import java.util.Locale
import kotlin.math.round

/** How much of a location a one-off location card gives away. */
enum class LocationPrecision(val wire: String, val plusCodeLength: Int) {
    /** The fix as it is (5 decimals, ~1 m); 10-digit Plus Code (~14 m). */
    EXACT("exact", 10),

    /** Rounded to 0.001° (~100 m); the Plus Code is of the rounded point. */
    APPROX("100m", 10),

    /** Only the geohash-5 cell centre (~5 km, the same cell as the area key); 6-digit Plus Code. */
    AREA("area", 6),
    ;

    companion object {
        fun of(wire: String): LocationPrecision? = entries.firstOrNull { it.wire == wire }
    }
}

/** One-off location cards: coarsening and Plus Codes. */
object LocationShare {
    /** Centre of Dhaka, the reference for short Plus Codes ("QJ6C+QX Dhaka"). */
    const val DHAKA_LAT = 23.8103
    const val DHAKA_LNG = 90.4125
    const val DHAKA = "Dhaka"

    /** The point that is actually shared for [precision]. */
    fun coarsen(lat: Double, lng: Double, precision: LocationPrecision): Pair<Double, Double> = when (precision) {
        LocationPrecision.EXACT -> roundTo(lat, 5) to roundTo(lng, 5)
        LocationPrecision.APPROX -> roundTo(lat, 3) to roundTo(lng, 3)
        LocationPrecision.AREA -> {
            val cell = GeoHash.encode(lat, lng, 5)
            val (cLat, cLng) = geohashCenter(cell)
            roundTo(cLat, 4) to roundTo(cLng, 4)
        }
    }

    /** Full Plus Code of an already coarsened point. */
    fun plusCode(lat: Double, lng: Double, precision: LocationPrecision): String =
        OpenLocationCode.encode(lat, lng, precision.plusCodeLength)

    /** Short code with the locality when near Dhaka ("QJ6C+QX Dhaka"), else the full code. */
    fun displayCode(fullCode: String): String {
        val short = OpenLocationCode.shorten(fullCode, DHAKA_LAT, DHAKA_LNG)
        // A two-digit shortening covers hundreds of kilometres, not the named locality.
        return if (short.indexOf('+') in 0..4) "$short $DHAKA" else fullCode.uppercase()
    }

    /** A location card for [lat]/[lng] at [precision], titled [title], with an optional [place] note. */
    fun card(lat: Double, lng: Double, precision: LocationPrecision, title: String, place: String = ""): ChatCard.Location {
        val (sLat, sLng) = coarsen(lat, lng, precision)
        return ChatCard.Location(sLat, sLng, precision, title, plusCode(sLat, sLng, precision), place)
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10 }
        return round(value * f) / f
    }

    private fun geohashCenter(hash: String): Pair<Double, Double> {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        var even = true
        for (ch in hash) {
            val v = GeoHash.BASE32.indexOf(ch)
            for (bit in 4 downTo 0) {
                val on = (v shr bit) and 1 == 1
                if (even) {
                    val mid = (lngMin + lngMax) / 2
                    if (on) lngMin = mid else lngMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (on) latMin = mid else latMax = mid
                }
                even = !even
            }
        }
        return (latMin + latMax) / 2 to (lngMin + lngMax) / 2
    }
}

/**
 * Structured cards inside chat rumors, carried as one `["card", type, …]` tag (the rumor's content keeps a
 * readable summary for clients that don't know the tag). Location cards come from "Share my location";
 * task, IOU, check-in and pin cards are rendered from tags now and produced by their own milestones.
 */
sealed interface ChatCard {
    fun toTag(): List<String>

    /** Text for the rumor's content, so other Nostr clients show something sensible. */
    fun summary(): String

    data class Location(
        val lat: Double,
        val lng: Double,
        val precision: LocationPrecision,
        val title: String,
        val plusCode: String,
        val place: String = "",
    ) : ChatCard {
        override fun toTag() = listOf(TAG, LOCATION, fmt(lat), fmt(lng), precision.wire, title, plusCode, place)
        override fun summary() = "$title · $plusCode · https://plus.codes/$plusCode"
    }

    data class Task(
        val type: String,
        val price: String,
        val title: String,
        val stepsDone: Int,
        val status: String,
        val runner: String = "",
    ) : ChatCard {
        override fun toTag() = listOf(TAG, TASK, type, price, title, stepsDone.toString(), status, runner)
        override fun summary() = "$type · $price · $title · $status"
    }

    /** IOU; [lent] is from the author's side (true: the other person owes the author). */
    data class Iou(
        val amount: String,
        val lent: Boolean,
        val what: String,
        val status: String,
        val date: String,
        val agreed: Boolean,
    ) : ChatCard {
        override fun toTag() = listOf(TAG, IOU, amount, if (lent) "lent" else "borrowed", what, status, date, if (agreed) "1" else "0")
        override fun summary() = "$amount · $what · $status"
    }

    data class CheckIn(val title: String, val detail: String) : ChatCard {
        override fun toTag() = listOf(TAG, CHECK_IN, title, detail)
        override fun summary() = "$title · $detail"
    }

    data class Pin(
        val title: String,
        val detail: String,
        val visibility: String,
        val yes: Int,
        val no: Int,
        val category: String,
    ) : ChatCard {
        override fun toTag() = listOf(TAG, PIN, title, detail, visibility, yes.toString(), no.toString(), category)
        override fun summary() = "$title · $detail"
    }

    companion object {
        const val TAG = "card"
        const val LOCATION = "location"
        const val TASK = "task"
        const val IOU = "iou"
        const val CHECK_IN = "checkin"
        const val PIN = "pin"

        /** The first card tag in [tags], or null when there is none or it is malformed. */
        fun from(tags: List<List<String>>): ChatCard? = tags.firstOrNull { it.size > 1 && it[0] == TAG }?.let(::parse)

        fun parse(tag: List<String>): ChatCard? = runCatching {
            if (tag.size < 2 || tag[0] != TAG) return null
            fun at(i: Int) = tag.getOrElse(i) { "" }.take(MAX_FIELD)
            when (tag[1]) {
                LOCATION -> {
                    val lat = tag[2].toDouble()
                    val lng = tag[3].toDouble()
                    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
                    Location(lat, lng, LocationPrecision.of(tag[4]) ?: return null, at(5), at(6), at(7))
                }
                TASK -> Task(at(2), at(3), at(4), tag[5].toInt().coerceIn(0, 5), at(6), at(7))
                IOU -> Iou(at(2), tag[3] == "lent", at(4), at(5), at(6), tag.getOrNull(7) == "1")
                CHECK_IN -> CheckIn(at(2), at(3))
                PIN -> Pin(at(2), at(3), at(4), tag[5].toInt().coerceAtLeast(0), tag[6].toInt().coerceAtLeast(0), at(7))
                else -> null
            }
        }.getOrNull()

        private const val MAX_FIELD = 300

        private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.5f", value)
    }
}
