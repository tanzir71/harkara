package io.github.tanzir71.harkara.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A latitude/longitude pair in degrees. */
data class LatLng(val lat: Double, val lng: Double)

/** Geohash decoding, neighbours and distances (pure math, no network). */
object GeoMath {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Bounding box of a geohash cell as (latMin, latMax, lngMin, lngMax). */
    fun bounds(geohash: String): DoubleArray {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        var even = true
        for (c in geohash.lowercase()) {
            val value = BASE32.indexOf(c)
            require(value >= 0) { "invalid geohash character '$c'" }
            for (bit in 4 downTo 0) {
                val on = (value shr bit) and 1 == 1
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
        return doubleArrayOf(latMin, latMax, lngMin, lngMax)
    }

    /** Centre of a geohash cell. */
    fun decode(geohash: String): LatLng {
        val b = bounds(geohash)
        return LatLng((b[0] + b[1]) / 2, (b[2] + b[3]) / 2)
    }

    /** The cell and its eight neighbours at the same precision. */
    fun withNeighbours(geohash: String): List<String> {
        val b = bounds(geohash)
        val dLat = b[1] - b[0]
        val dLng = b[3] - b[2]
        val centre = decode(geohash)
        val out = LinkedHashSet<String>()
        for (dy in -1..1) for (dx in -1..1) {
            val lat = (centre.lat + dy * dLat).coerceIn(-89.999999, 89.999999)
            var lng = centre.lng + dx * dLng
            if (lng > 180) lng -= 360
            if (lng < -180) lng += 360
            out += GeoHash.encode(lat, lng, geohash.length)
        }
        return out.toList()
    }

    /** Great-circle distance in metres. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** "350 m" / "1.1 km" style label. */
    fun distanceLabel(meters: Double): String =
        if (meters < 1000) "${(meters / 10).toInt() * 10} m" else "%.1f km".format(java.util.Locale.ROOT, meters / 1000)
}
