package io.github.tanzir71.harkara.core.geo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
}

/**
 * Open Location Code (Plus Codes), written from the public specification
 * (github.com/google/open-location-code, Documentation/Specification). Integer arithmetic as in the
 * reference implementations, so codes agree with theirs at every length.
 */
object OpenLocationCode {
    const val SEPARATOR: Char = '+'
    const val SEPARATOR_POSITION: Int = 8
    const val PADDING: Char = '0'
    const val ALPHABET: String = "23456789CFGHJMPQRVWX"

    private const val BASE = 20
    private const val LAT_MAX = 90
    private const val LNG_MAX = 180
    private const val MAX_DIGITS = 15
    private const val PAIR_CODE_LENGTH = 10
    private const val GRID_CODE_LENGTH = MAX_DIGITS - PAIR_CODE_LENGTH
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5
    private const val PAIR_PRECISION = 8_000L
    private const val PAIR_FIRST_PLACE_VALUE = 160_000L
    private const val GRID_LAT_FIRST_PLACE_VALUE = 625L
    private const val GRID_LNG_FIRST_PLACE_VALUE = 256L
    private const val FINAL_LAT_PRECISION = PAIR_PRECISION * 3_125L
    private const val FINAL_LNG_PRECISION = PAIR_PRECISION * 1_024L
    private const val MIN_TRIMMABLE_CODE_LEN = 6
    private val PAIR_RESOLUTIONS = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

    /** Full code for a point; [codeLength] 2, 4, 6, 8 or 10–15 digits (10 ≈ 14 m, 6 ≈ 5.5 km). */
    fun encode(latitude: Double, longitude: Double, codeLength: Int = PAIR_CODE_LENGTH): String {
        require(codeLength in 2..MAX_DIGITS && (codeLength >= PAIR_CODE_LENGTH || codeLength % 2 == 0)) { "invalid code length $codeLength" }
        var latVal = Math.floorDiv(Math.round((latitude + LAT_MAX) * FINAL_LAT_PRECISION * 1e6), 1_000_000L)
        latVal = latVal.coerceIn(0L, 2L * LAT_MAX * FINAL_LAT_PRECISION - 1)
        var lngVal = Math.floorDiv(Math.round((longitude + LNG_MAX) * FINAL_LNG_PRECISION * 1e6), 1_000_000L)
        lngVal = Math.floorMod(lngVal, 2L * LNG_MAX * FINAL_LNG_PRECISION)

        val code = CharArray(MAX_DIGITS)
        if (codeLength > PAIR_CODE_LENGTH) {
            for (i in MAX_DIGITS - 1 downTo PAIR_CODE_LENGTH) {
                val latDigit = (latVal % GRID_ROWS).toInt()
                val lngDigit = (lngVal % GRID_COLUMNS).toInt()
                code[i] = ALPHABET[latDigit * GRID_COLUMNS + lngDigit]
                latVal /= GRID_ROWS
                lngVal /= GRID_COLUMNS
            }
        } else {
            repeat(GRID_CODE_LENGTH) {
                latVal /= GRID_ROWS
                lngVal /= GRID_COLUMNS
            }
        }
        for (i in PAIR_CODE_LENGTH / 2 - 1 downTo 0) {
            code[i * 2 + 1] = ALPHABET[(lngVal % BASE).toInt()]
            code[i * 2] = ALPHABET[(latVal % BASE).toInt()]
            latVal /= BASE
            lngVal /= BASE
        }
        val digits = String(code, 0, max(codeLength, SEPARATOR_POSITION))
        return if (codeLength >= SEPARATOR_POSITION) {
            digits.substring(0, SEPARATOR_POSITION) + SEPARATOR + digits.substring(SEPARATOR_POSITION, codeLength)
        } else {
            digits.substring(0, codeLength) + PADDING.toString().repeat(SEPARATOR_POSITION - codeLength) + SEPARATOR
        }
    }

    /** True for a well-formed full or short code (separator, padding and alphabet rules). */
    fun isValid(code: String): Boolean {
        val c = code.uppercase()
        val sep = c.indexOf(SEPARATOR)
        if (c.length == 1 || sep < 0 || sep != c.lastIndexOf(SEPARATOR) || sep > SEPARATOR_POSITION || sep % 2 == 1) return false
        val pad = c.indexOf(PADDING)
        if (pad >= 0) {
            if (sep < SEPARATOR_POSITION || pad == 0 || pad % 2 == 1) return false
            val padding = c.substring(pad, sep)
            if (padding.any { it != PADDING } || c.length > sep + 1) return false
        }
        if (c.length - sep - 1 == 1) return false
        return c.all { it == SEPARATOR || it == PADDING || it in ALPHABET }
    }

    fun isShort(code: String): Boolean = isValid(code) && code.indexOf(SEPARATOR) in 0 until SEPARATOR_POSITION

    fun isFull(code: String): Boolean {
        if (!isValid(code) || isShort(code)) return false
        val c = code.uppercase()
        if (ALPHABET.indexOf(c[0]) * BASE >= LAT_MAX * 2) return false
        return c.length < 2 || ALPHABET.indexOf(c[1]) * BASE < LNG_MAX * 2
    }

    /** The area a full code stands for. */
    fun decode(code: String): CodeArea {
        require(isFull(code)) { "not a full Plus Code: $code" }
        val clean = code.uppercase().filter { it != SEPARATOR && it != PADDING }.take(MAX_DIGITS)
        var normalLat = -LAT_MAX * PAIR_PRECISION
        var normalLng = -LNG_MAX * PAIR_PRECISION
        var gridLat = 0L
        var gridLng = 0L
        val pairDigits = min(clean.length, PAIR_CODE_LENGTH)
        var pv = PAIR_FIRST_PLACE_VALUE
        var i = 0
        while (i < pairDigits) {
            normalLat += ALPHABET.indexOf(clean[i]) * pv
            normalLng += ALPHABET.indexOf(clean[i + 1]) * pv
            if (i < pairDigits - 2) pv /= BASE
            i += 2
        }
        var latPrecision = pv.toDouble() / PAIR_PRECISION
        var lngPrecision = pv.toDouble() / PAIR_PRECISION
        if (clean.length > PAIR_CODE_LENGTH) {
            var rowPv = GRID_LAT_FIRST_PLACE_VALUE
            var colPv = GRID_LNG_FIRST_PLACE_VALUE
            for (j in PAIR_CODE_LENGTH until clean.length) {
                val d = ALPHABET.indexOf(clean[j])
                gridLat += (d / GRID_COLUMNS) * rowPv
                gridLng += (d % GRID_COLUMNS) * colPv
                if (j < clean.length - 1) {
                    rowPv /= GRID_ROWS
                    colPv /= GRID_COLUMNS
                }
            }
            latPrecision = rowPv.toDouble() / FINAL_LAT_PRECISION
            lngPrecision = colPv.toDouble() / FINAL_LNG_PRECISION
        }
        val lat = normalLat.toDouble() / PAIR_PRECISION + gridLat.toDouble() / FINAL_LAT_PRECISION
        val lng = normalLng.toDouble() / PAIR_PRECISION + gridLng.toDouble() / FINAL_LNG_PRECISION
        return CodeArea(lat, lng, lat + latPrecision, lng + lngPrecision, clean.length)
    }

    /**
     * Drops leading digits that a reference point makes obvious ("7MMGQJ6C+QX" near Dhaka → "QJ6C+QX").
     * Padded codes and codes far from the reference come back unchanged.
     */
    fun shorten(code: String, referenceLatitude: Double, referenceLongitude: Double): String {
        if (!isFull(code) || code.indexOf(PADDING) >= 0) return code.uppercase()
        val c = code.uppercase()
        val area = decode(c)
        if (area.codeLength < MIN_TRIMMABLE_CODE_LEN) return c
        val refLat = referenceLatitude.coerceIn(-90.0, 90.0)
        val refLng = ((referenceLongitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val range = max(abs(area.centerLatitude - refLat), abs(area.centerLongitude - refLng))
        for (i in PAIR_RESOLUTIONS.size - 2 downTo 1) {
            if (range < PAIR_RESOLUTIONS[i] * 0.3) return c.substring((i + 1) * 2)
        }
        return c
    }
}
