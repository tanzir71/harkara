package io.github.tanzir71.harkara.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Vectors from the Open Location Code test data (encoding.csv, validityTests.csv, shortCodeTests.csv). */
class OpenLocationCodeTest {
    @Test
    fun `encodes the reference vectors`() {
        val vectors = listOf(
            Triple(20.375, 2.775, 6) to "7FG49Q00+",
            Triple(20.3700625, 2.7821875, 10) to "7FG49QCJ+2V",
            Triple(47.0000625, 8.0000625, 10) to "8FVC2222+22",
            Triple(-41.2730625, 174.7859375, 10) to "4VCPPQGP+Q9",
            Triple(0.5, -179.5, 4) to "62G20000+",
            Triple(-89.5, -179.5, 4) to "22220000+",
            Triple(20.5, 2.5, 4) to "7FG40000+",
            Triple(-89.9999375, -179.9999375, 10) to "22222222+22",
            Triple(0.5, 179.5, 4) to "6VGX0000+",
            Triple(90.0, 1.0, 4) to "CFX30000+",
            Triple(92.0, 1.0, 4) to "CFX30000+",
            Triple(1.0, 180.0, 4) to "62H20000+",
            Triple(1.0, 181.0, 4) to "62H30000+",
        )
        vectors.forEach { (input, code) ->
            assertEquals(code, OpenLocationCode.encode(input.first, input.second, input.third), "for $input")
        }
    }

    @Test
    fun `dhaka codes start with 7MMG`() {
        assertTrue(OpenLocationCode.encode(23.7808, 90.4142).startsWith("7MMG"))
        assertEquals(12, OpenLocationCode.encode(23.7808, 90.4142, 11).length)
    }

    @Test
    fun `decode contains the encoded point at every length`() {
        // Off any cell boundary, so double rounding in the decoded corners cannot matter.
        val lat = 23.7465123
        val lng = 90.3760457
        for (length in listOf(2, 4, 6, 8, 10, 11, 12, 13, 14, 15)) {
            val area = OpenLocationCode.decode(OpenLocationCode.encode(lat, lng, length))
            assertTrue(lat >= area.latitudeLo && lat < area.latitudeHi, "lat at $length")
            assertTrue(lng >= area.longitudeLo && lng < area.longitudeHi, "lng at $length")
            assertEquals(length, area.codeLength)
        }
        val ten = OpenLocationCode.decode("7FG49QCJ+2V")
        assertTrue(abs(ten.centerLatitude - 20.3700625) < 1e-9)
        assertTrue(abs(ten.centerLongitude - 2.7821875) < 1e-9)
    }

    @Test
    fun `validity rules`() {
        listOf("8FWC2345+G6", "8FWC2345+G6G", "8fwc2345+", "8FWCX400+", "WC2345+G6g", "2345+G6", "45+G6", "+G6").forEach {
            assertTrue(OpenLocationCode.isValid(it), it)
        }
        listOf("G+", "+", "8FWC2345+G", "8FWC2_45+G6", "8FWC2η45+G6", "8FWC2345+G6+", "8FWC2345G6+", "8FWC2300+G6", "WC2300+G6g", "WC2345+G").forEach {
            assertFalse(OpenLocationCode.isValid(it), it)
        }
        assertTrue(OpenLocationCode.isFull("8FWC2345+G6"))
        assertFalse(OpenLocationCode.isFull("WC2345+G6g"))
        assertTrue(OpenLocationCode.isShort("2345+G6"))
        assertThrows(IllegalArgumentException::class.java) { OpenLocationCode.decode("2345+G6") }
        assertThrows(IllegalArgumentException::class.java) { OpenLocationCode.encode(1.0, 1.0, 9) }
    }

    @Test
    fun `shortens relative to a nearby reference`() {
        assertEquals("+2VX", OpenLocationCode.shorten("9C3W9QCJ+2VX", 51.3701125, -1.217765625))
        assertEquals("CJ+2VX", OpenLocationCode.shorten("9C3W9QCJ+2VX", 51.3708675, -1.217765625))
        assertEquals("9QCJ+2VX", OpenLocationCode.shorten("9C3W9QCJ+2VX", 51.3701125, -1.3))
        assertEquals("9C3W9QCJ+2VX", OpenLocationCode.shorten("9C3W9QCJ+2VX", 1.0, 1.0))
        val dhaka = OpenLocationCode.encode(23.7465, 90.376)
        assertEquals(dhaka.substring(4), OpenLocationCode.shorten(dhaka, 23.8103, 90.4125))
        assertEquals("7MMG0000+", OpenLocationCode.shorten("7MMG0000+", 23.8103, 90.4125))
    }
}
