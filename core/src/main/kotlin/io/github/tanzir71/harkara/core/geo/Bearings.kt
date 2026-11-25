package io.github.tanzir71.harkara.core.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Compass math for the meet-up arrow, the compass tool and the Lite list (pure, no sensors). */
object Bearings {
    private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Initial great-circle bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
    fun initial(from: LatLng, to: LatLng): Double {
        val p1 = Math.toRadians(from.lat)
        val p2 = Math.toRadians(to.lat)
        val dl = Math.toRadians(to.lng - from.lng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Where the target is relative to where the phone points: rotate the arrow by this, in (-180, 180]. */
    fun relative(bearing: Double, heading: Double): Double {
        val d = normalize(bearing - heading)
        return if (d > 180) d - 360 else d
    }

    /** Magnetic heading to true heading (Dhaka's declination is about -0.5°, so this barely matters there). */
    fun trueHeading(magnetic: Double, declination: Double): Double = normalize(magnetic + declination)

    fun normalize(degrees: Double): Double {
        val d = degrees % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /** Eight-point label ("NE") for a bearing. */
    fun cardinal(bearing: Double): String = CARDINALS[((normalize(bearing) + 22.5) / 45.0).toInt() % 8]

    /** Whole degrees for display, 0..359. */
    fun degrees(bearing: Double): Int = normalize(bearing).roundToInt() % 360

    /** A point [meters] away from [from] along [bearing] (used to place test fixtures and the "~100 m" grid). */
    fun destination(from: LatLng, bearing: Double, meters: Double): LatLng {
        val r = 6_371_000.0
        val d = meters / r
        val b = Math.toRadians(bearing)
        val p1 = Math.toRadians(from.lat)
        val l1 = Math.toRadians(from.lng)
        val p2 = kotlin.math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLng(Math.toDegrees(p2), (Math.toDegrees(l2) + 540) % 360 - 180)
    }
}

/** Distance and direction to something, ready for the UI. */
data class Heading(val distanceMeters: Double, val bearing: Double) {
    val cardinal: String get() = Bearings.cardinal(bearing)

    companion object {
        fun between(from: LatLng, to: LatLng) = Heading(GeoMath.distanceMeters(from, to), Bearings.initial(from, to))
    }
}
