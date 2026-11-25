package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.geo.OpenLocationCode
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor

/** How much of a position a one-off location card gives away. */
enum class LocationPrecision(val wire: String, val plusCodeLength: Int) {
    /** The fix as is (5 decimals, about 1 m). */
    EXACT("exact", 10),
    /** Snapped to the centre of a ~100 m grid cell, so the exact spot is never sent. */
    HUNDRED_METERS("100m", 10),
    /** Only the ~5 km geohash-5 cell centre and the area's name. */
    AREA("area", 6);

    companion object {
        fun of(wire: String): LocationPrecision? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A one-off location card. In chats it rides as one tag on the message rumor:
 * `["card", "location", lat, lng, precision, label, pluscode]` (lat/lng with 5 decimals, Latin digits).
 */
data class LocationCardData(
    val lat: Double,
    val lng: Double,
    val precision: LocationPrecision,
    val label: String,
    val plusCode: String,
) {
    val position: LatLng get() = LatLng(lat, lng)

    fun toTag(): List<String> = listOf(TAG, KIND, fmt(lat), fmt(lng), precision.wire, label, plusCode)

    fun toTags(): List<List<String>> = listOf(toTag())

    /** "Q8V2+QX Dhaka" style: the code without the digits Dhaka already implies. */
    fun shortPlusCode(): String =
        if (precision == LocationPrecision.AREA || !OpenLocationCode.isFull(plusCode)) plusCode
        else OpenLocationCode.shorten(plusCode, DHAKA.lat, DHAKA.lng, maxDigits = 4) + " Dhaka"

    /** Plain text for apps without Harkara (SMS, other messengers): code, label and an OpenStreetMap link. */
    fun shareText(): String {
        val zoom = when (precision) {
            LocationPrecision.EXACT -> 18
            LocationPrecision.HUNDRED_METERS -> 16
            LocationPrecision.AREA -> 13
        }
        return listOf(
            listOf(label, shortPlusCode()).filter { it.isNotBlank() }.joinToString(" · "),
            "https://www.openstreetmap.org/?mlat=${fmt(lat)}&mlon=${fmt(lng)}#map=$zoom/${fmt(lat)}/${fmt(lng)}",
        ).joinToString("\n")
    }

    companion object {
        const val TAG = "card"
        const val KIND = "location"
        val DHAKA = LatLng(23.8103, 90.4125)

        /** Grid step: 100 m of latitude. */
        private const val GRID_DEGREES = 100.0 / 111_320.0

        private fun fmt(v: Double) = String.format(Locale.ROOT, "%.5f", v)

        /**
         * Builds a card for a fix at the chosen precision. [areaName] labels area cards and is the default
         * label otherwise.
         */
        fun make(at: LatLng, precision: LocationPrecision, areaName: String?, label: String? = null): LocationCardData {
            val point = reduce(at, precision)
            val text = label?.takeIf { it.isNotBlank() } ?: areaName.orEmpty()
            return LocationCardData(
                round5(point.lat), round5(point.lng), precision,
                text.take(MAX_LABEL),
                OpenLocationCode.encode(point.lat, point.lng, precision.plusCodeLength),
            )
        }

        /** The position actually shared for a precision. */
        fun reduce(at: LatLng, precision: LocationPrecision): LatLng = when (precision) {
            LocationPrecision.EXACT -> at
            LocationPrecision.HUNDRED_METERS -> {
                val lat = floor(at.lat / GRID_DEGREES) * GRID_DEGREES + GRID_DEGREES / 2
                val lngStep = GRID_DEGREES / cos(Math.toRadians(lat))
                LatLng(lat, floor(at.lng / lngStep) * lngStep + lngStep / 2)
            }
            LocationPrecision.AREA -> GeoMath.decode(GeoHash.encode(at.lat, at.lng, 5))
        }

        private fun round5(v: Double) = Math.round(v * 100_000.0) / 100_000.0

        const val MAX_LABEL = 60

        fun fromTag(tag: List<String>): LocationCardData? {
            if (tag.size < 7 || tag[0] != TAG || tag[1] != KIND) return null
            val lat = tag[2].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val lng = tag[3].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            val precision = LocationPrecision.of(tag[4]) ?: return null
            val code = tag[6].takeIf { it.isEmpty() || OpenLocationCode.isFull(it) } ?: return null
            return LocationCardData(lat, lng, precision, tag[5].take(MAX_LABEL), code.uppercase())
        }

        /** The first location card among an event's tags. */
        fun fromTags(tags: List<List<String>>): LocationCardData? = tags.firstNotNullOfOrNull(::fromTag)
    }
}
