package io.github.tanzir71.harkara.core.geo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The rectangle a Plus Code stands for, with its centre. */
data class CodeArea(
    val latitudeLo: Double,
    val longitudeLo: Double,
    val latitudeHi: Double,
    val longitudeHi: Double,
    val codeLength: Int,
) {
    val centerLatitude: Double get() = min((latitudeLo + latitudeHi) / 2, 90.0)
    val centerLongitude: Double get() = min((longitudeLo + longitudeHi) / 2, 180.0)
    val south: Double get() = latitudeLo
    val west: Double get() = longitudeLo
    val north: Double get() = latitudeHi
    val east: Double get() = longitudeHi
    val length: Int get() = codeLength
    val centerLat: Double get() = centerLatitude
    val centerLng: Double get() = centerLongitude
}

/**
 * Open Location Code (Plus Codes), written from the specification at github.com/google/open-location-code
 * and its integer-based reference algorithm (Apache-2.0), checked against the official test data.
 */
object OpenLocationCode {
    const val ALPHABET = "23456789CFGHJMPQRVWX"
    const val SEPARATOR = '+'
    const val PADDING = '0'
    const val NORMAL_LENGTH = 10
    const val SEPARATOR_POSITION = 8
    private const val MIN_DIGITS = 2
    private const val MAX_DIGITS = 15
    private const val PAIR_LENGTH = 10
    private const val GRID_LENGTH = MAX_DIGITS - PAIR_LENGTH
    private const val BASE = 20
    private const val LAT_MAX = 90L
    private const val LNG_MAX = 180L
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5
    private const val LAT_MULTIPLIER = 8000L * 3125
    private const val LNG_MULTIPLIER = 8000L * 1024
    private const val LAT_MSP = LAT_MULTIPLIER * BASE * BASE
    private const val LNG_MSP = LNG_MULTIPLIER * BASE * BASE

    fun encode(latitude: Double, longitude: Double, codeLength: Int = NORMAL_LENGTH): String {
        val (lat, lng) = degreesToIntegers(latitude, longitude)
        return encodeIntegers(lat, lng, codeLength)
    }

    /** Clipped/normalised integer form of a position, as the reference implementation computes it. */
    fun degreesToIntegers(latitude: Double, longitude: Double): Pair<Long, Long> {
        var lat = floor(latitude * LAT_MULTIPLIER).toLong()
        var lng = floor(longitude * LNG_MULTIPLIER).toLong()
        lat += LAT_MAX * LAT_MULTIPLIER
        if (lat < 0) lat = 0 else if (lat >= 2 * LAT_MAX * LAT_MULTIPLIER) lat = 2 * LAT_MAX * LAT_MULTIPLIER - 1
        lng += LNG_MAX * LNG_MULTIPLIER
        lng = Math.floorMod(lng, 2 * LNG_MAX * LNG_MULTIPLIER)
        return lat to lng
    }

    fun encodeIntegers(latInt: Long, lngInt: Long, length: Int): String {
        val codeLength = min(length, MAX_DIGITS)
        require(!(codeLength < PAIR_LENGTH && codeLength % 2 == 1) && codeLength >= MIN_DIGITS) { "illegal code length $length" }
        var lat = latInt
        var lng = lngInt
        val rev = StringBuilder()
        if (codeLength > PAIR_LENGTH) {
            repeat(GRID_LENGTH) {
                val latDigit = lat % GRID_ROWS
                val lngDigit = lng % GRID_COLUMNS
                rev.append(ALPHABET[(latDigit * GRID_COLUMNS + lngDigit).toInt()])
                lat /= GRID_ROWS
                lng /= GRID_COLUMNS
            }
        } else {
            lat /= GRID_ROWS.toDouble().pow(GRID_LENGTH).toLong()
            lng /= GRID_COLUMNS.toDouble().pow(GRID_LENGTH).toLong()
        }
        for (i in 0 until PAIR_LENGTH / 2) {
            rev.append(ALPHABET[(lng % BASE).toInt()])
            rev.append(ALPHABET[(lat % BASE).toInt()])
            lat /= BASE
            lng /= BASE
            if (i == 0) rev.append(SEPARATOR)
        }
        val code = rev.reverse()
        if (codeLength < SEPARATOR_POSITION) for (i in codeLength until SEPARATOR_POSITION) code.setCharAt(i, PADDING)
        return code.substring(0, max(SEPARATOR_POSITION + 1, codeLength + 1))
    }

    fun decode(code: String): CodeArea {
        require(isFull(code)) { "not a full Open Location Code: $code" }
        val clean = code.uppercase().replace(SEPARATOR.toString(), "").replace(PADDING.toString(), "")
        var latVal = -LAT_MAX * LAT_MULTIPLIER
        var lngVal = -LNG_MAX * LNG_MULTIPLIER
        var latPlace = LAT_MSP
        var lngPlace = LNG_MSP
        var i = 0
        while (i < min(clean.length, PAIR_LENGTH)) {
            latPlace /= BASE
            lngPlace /= BASE
            latVal += ALPHABET.indexOf(clean[i]) * latPlace
            lngVal += ALPHABET.indexOf(clean[i + 1]) * lngPlace
            i += 2
        }
        for (j in PAIR_LENGTH until min(clean.length, MAX_DIGITS)) {
            latPlace /= GRID_ROWS
            lngPlace /= GRID_COLUMNS
            val digit = ALPHABET.indexOf(clean[j])
            latVal += (digit / GRID_COLUMNS) * latPlace
            lngVal += (digit % GRID_COLUMNS) * lngPlace
        }
        return CodeArea(
            latVal.toDouble() / LAT_MULTIPLIER,
            lngVal.toDouble() / LNG_MULTIPLIER,
            (latVal + latPlace).toDouble() / LAT_MULTIPLIER,
            (lngVal + lngPlace).toDouble() / LNG_MULTIPLIER,
            min(clean.length, MAX_DIGITS),
        )
    }

    fun isValid(input: String?): Boolean {
        if (input == null || input.length < 2) return false
        val code = input.uppercase()
        val sep = code.indexOf(SEPARATOR)
        if (sep == -1 || sep != code.lastIndexOf(SEPARATOR)) return false
        if (sep % 2 != 0 || sep > SEPARATOR_POSITION) return false
        if (sep == SEPARATOR_POSITION) {
            if (ALPHABET.indexOf(code[0]) > 8) return false
            if (ALPHABET.indexOf(code[1]) > 17) return false
        }
        var padding = false
        for (i in 0 until sep) {
            val ch = code[i]
            if (ALPHABET.indexOf(ch) == -1 && ch != PADDING) return false
            if (padding) {
                if (ch != PADDING) return false
            } else if (ch == PADDING) {
                padding = true
                if (sep < SEPARATOR_POSITION) return false
                if (i != 2 && i != 4 && i != 6) return false
            }
        }
        if (code.length > sep + 1) {
            if (padding) return false
            if (code.length == sep + 2) return false
            for (i in sep + 1 until code.length) if (ALPHABET.indexOf(code[i]) == -1) return false
        }
        return true
    }

    fun isFull(code: String): Boolean = isValid(code) && code.indexOf(SEPARATOR) == SEPARATOR_POSITION

    fun isShort(code: String): Boolean = isValid(code) && code.indexOf(SEPARATOR) in 0 until SEPARATOR_POSITION

    /** Drops leading digits made obvious by the reference point; padded location cards stay intact. */
    fun shorten(code: String, referenceLatitude: Double, referenceLongitude: Double, maxDigits: Int = 8): String {
        if (!isFull(code) || code.indexOf(PADDING) >= 0) return code.uppercase()
        val area = decode(code)
        val refLat = referenceLatitude.coerceIn(-90.0, 90.0)
        val refLng = normalizeLongitude(referenceLongitude)
        val range = max(abs(refLat - area.centerLat), abs(refLng - area.centerLng))
        for (i in 4 downTo 1) {
            if (i * 2 <= maxDigits && range < latitudePrecision(i * 2) * 0.3) return code.uppercase().substring(i * 2)
        }
        return code.uppercase()
    }

    /** The nearest full code to the reference point that ends with [shortCode]. */
    fun recover(shortCode: String, refLat: Double, refLng: Double): String {
        if (isFull(shortCode)) return shortCode.uppercase()
        require(isShort(shortCode)) { "not a valid short code" }
        val lat0 = min(max(refLat, -LAT_MAX.toDouble()), LAT_MAX.toDouble())
        val lng0 = normalizeLongitude(refLng)
        val digits = SEPARATOR_POSITION - shortCode.indexOf(SEPARATOR)
        val precision = BASE.toDouble().pow(2 - digits / 2)
        val prefix = encode(lat0, lng0).substring(0, digits)
        val recovered = prefix + shortCode.uppercase()
        val area = decode(recovered)
        var lat = area.centerLat
        var lng = area.centerLng
        val dLat = lat - lat0
        if (dLat > precision / 2 && lat - precision > -LAT_MAX) lat -= precision
        else if (dLat < -precision / 2 && lat + precision < LAT_MAX) lat += precision
        val dLng = area.centerLng - lng0
        if (dLng > precision / 2) lng -= precision else if (dLng < -precision / 2) lng += precision
        return encode(lat, lng, recovered.length - 1)
    }

    private fun normalizeLongitude(longitude: Double): Double {
        if (longitude >= -LNG_MAX && longitude < LNG_MAX) return longitude
        val circle = 2 * LNG_MAX
        return (longitude % circle + circle + LNG_MAX) % circle - LNG_MAX
    }

    private fun latitudePrecision(length: Int): Double =
        if (length <= NORMAL_LENGTH) BASE.toDouble().pow((length / -2 + 2).toDouble())
        else BASE.toDouble().pow(-3) / GRID_ROWS.toDouble().pow(length - PAIR_LENGTH)
}
