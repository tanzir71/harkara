package io.github.tanzir71.harkara.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Official test data from github.com/google/open-location-code/test_data (Apache-2.0). */
class OpenLocationCodeTest {
    private fun rows(name: String): List<List<String>> =
        javaClass.getResourceAsStream("/olc/$name")!!.bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(',') }

    @Test
    fun `encoding vectors - integers exactly, degrees within the reference tolerance`() {
        val data = rows("encoding.csv")
        assertTrue(data.size > 250)
        var degreeMisses = 0
        data.forEach { (lat, lng, latInt, lngInt, len, code) ->
            assertEquals(code, OpenLocationCode.encodeIntegers(latInt.toLong(), lngInt.toLong(), len.toInt()), "integers $latInt,$lngInt")
            val (gotLat, gotLng) = OpenLocationCode.degreesToIntegers(lat.toDouble(), lng.toDouble())
            assertTrue(abs(gotLat - latInt.toLong()) <= 1 && abs(gotLng - lngInt.toLong()) <= 1, "degrees to integers $lat,$lng")
            if (OpenLocationCode.encode(lat.toDouble(), lng.toDouble(), len.toInt()) != code) degreeMisses++
        }
        // The reference tests allow 5 % of floating-point encodings to land in the neighbouring cell.
        assertTrue(degreeMisses <= data.size * 0.05, "$degreeMisses misses")
    }

    @Test
    fun `decoding vectors`() {
        rows("decoding.csv").forEach { (code, len, latLo, lngLo, latHi, lngHi) ->
            val area = OpenLocationCode.decode(code)
            assertEquals(len.toInt(), area.length, code)
            assertEquals(latLo.toDouble(), area.south, 1e-10, code)
            assertEquals(lngLo.toDouble(), area.west, 1e-10, code)
            assertEquals(latHi.toDouble(), area.north, 1e-10, code)
            assertEquals(lngHi.toDouble(), area.east, 1e-10, code)
        }
    }

    @Test
    fun `validity vectors`() {
        rows("validityTests.csv").forEach { (code, valid, short, full) ->
            assertEquals(valid.toBoolean(), OpenLocationCode.isValid(code), "valid $code")
            assertEquals(short.toBoolean(), OpenLocationCode.isShort(code), "short $code")
            assertEquals(full.toBoolean(), OpenLocationCode.isFull(code), "full $code")
        }
    }

    @Test
    fun `shorten and recover vectors`() {
        rows("shortCodeTests.csv").forEach { (full, lat, lng, short, type) ->
            if (type == "S" || type == "B") assertEquals(short, OpenLocationCode.shorten(full, lat.toDouble(), lng.toDouble()), "shorten $full")
            if (type == "R" || type == "B") assertEquals(full, OpenLocationCode.recover(short, lat.toDouble(), lng.toDouble()), "recover $short")
        }
    }

    @Test
    fun `a Dhanmondi code reads as four digits plus Dhaka`() {
        val code = OpenLocationCode.encode(23.7465, 90.3760)
        assertTrue(code.startsWith("7MMG"))
        val short = OpenLocationCode.shorten(code, 23.8103, 90.4125, maxDigits = 4)
        assertEquals(7, short.length)
        assertEquals(code, OpenLocationCode.recover(short, 23.8103, 90.4125))
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
