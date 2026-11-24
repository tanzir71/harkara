package io.github.tanzir71.harkara.core.geo

/** Geohash encoding for area tags (`g`) and the weekly area key scope (geohash-5, about 4.9 km x 4.9 km). */
object GeoHash {
    const val BASE32: String = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Encodes a latitude/longitude into a geohash of [precision] characters. */
    fun encode(latitude: Double, longitude: Double, precision: Int = 6): String {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(precision > 0) { "precision must be positive" }
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var even = true
        var bits = 0
        var value = 0
        val result = StringBuilder()
        while (result.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (longitude >= mid) {
                    value = (value shl 1) or 1
                    lonMin = mid
                } else {
                    value = value shl 1
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    value = (value shl 1) or 1
                    latMin = mid
                } else {
                    value = value shl 1
                    latMax = mid
                }
            }
            even = !even
            bits += 1
            if (bits == 5) {
                result.append(BASE32[value])
                bits = 0
                value = 0
            }
        }
        return result.toString()
    }

    /** The geohash-5 cell that scopes the weekly anonymous area key. */
    fun area5(latitude: Double, longitude: Double): String = encode(latitude, longitude, 5)

    fun isValid(geohash: String): Boolean = geohash.isNotEmpty() && geohash.all { it in BASE32 }
}
