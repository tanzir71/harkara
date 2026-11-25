package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.geo.Bearings
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.Heading
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.geo.OpenLocationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationCardTest {
    private val lake = LatLng(23.74651, 90.37602)

    @Test
    fun `bearing and distance between known points`() {
        val origin = LatLng(23.75, 90.38)
        assertEquals(0.0, Bearings.initial(origin, LatLng(23.76, 90.38)), 1e-6)
        assertEquals(180.0, Bearings.initial(origin, LatLng(23.74, 90.38)), 1e-6)
        assertEquals(90.0, Bearings.initial(origin, LatLng(23.75, 90.39)), 0.01)
        assertEquals(270.0, Bearings.initial(origin, LatLng(23.75, 90.37)), 0.01)
        // Dhaka -> Chattogram (22.3569, 91.7832): about 140°, about 214 km as the crow flies.
        val ctg = Heading.between(LatLng(23.8103, 90.4125), LatLng(22.3569, 91.7832))
        assertEquals(140.0, ctg.bearing, 1.5)
        assertEquals(214_000.0, ctg.distanceMeters, 2_000.0)
        assertEquals("SE", ctg.cardinal)
        for (b in listOf(0.0, 37.0, 91.0, 222.0, 359.0)) {
            val there = Bearings.destination(origin, b, 500.0)
            assertEquals(500.0, GeoMath.distanceMeters(origin, there), 0.5)
            assertEquals(b, Bearings.initial(origin, there), 0.05)
        }
    }

    @Test
    fun `arrow turns by bearing minus heading`() {
        assertEquals(30.0, Bearings.relative(120.0, 90.0), 1e-9)
        assertEquals(-30.0, Bearings.relative(60.0, 90.0), 1e-9)
        assertEquals(-20.0, Bearings.relative(350.0, 10.0), 1e-9)
        assertEquals(20.0, Bearings.relative(10.0, 350.0), 1e-9)
        assertEquals(180.0, Bearings.relative(180.0, 0.0), 1e-9)
        assertEquals("N", Bearings.cardinal(359.0))
        assertEquals("NE", Bearings.cardinal(30.0))
        assertEquals("W", Bearings.cardinal(-90.0))
        assertEquals(359.5, Bearings.trueHeading(0.0, -0.5), 1e-9)
        assertEquals(0, Bearings.degrees(359.7))
    }

    @Test
    fun `precision reduces what is shared`() {
        val exact = LocationCardData.make(lake, LocationPrecision.EXACT, "Dhanmondi")
        assertEquals(lake.lat, exact.lat, 1e-9)
        assertEquals(11, exact.plusCode.length)
        assertTrue(OpenLocationCode.decode(exact.plusCode).let { lake.lat in it.south..it.north && lake.lng in it.west..it.east })

        val hundred = LocationCardData.make(lake, LocationPrecision.HUNDRED_METERS, "Dhanmondi")
        val moved = GeoMath.distanceMeters(lake, hundred.position)
        assertTrue(moved <= 71.0, "a 100 m cell centre is at most ~71 m away, was $moved")
        // Every fix in the same cell gives the same card, so repeated shares can't be averaged.
        val nearby = LocationCardData.make(LatLng(hundred.lat + 0.0002, hundred.lng - 0.0002), LocationPrecision.HUNDRED_METERS, "Dhanmondi")
        assertEquals(hundred.position, nearby.position)

        val area = LocationCardData.make(lake, LocationPrecision.AREA, "Dhanmondi")
        assertEquals(GeoHash.encode(lake.lat, lake.lng, 5), GeoHash.encode(area.lat, area.lng, 5))
        assertEquals(GeoMath.decode(GeoHash.encode(lake.lat, lake.lng, 5)).lat, area.lat, 1e-5)
        assertTrue(area.plusCode.endsWith("00+"))
        assertEquals("Dhanmondi", area.label)
        assertEquals(area.plusCode, area.shortPlusCode())
    }

    @Test
    fun `card tags round-trip and junk is refused`() {
        val card = LocationCardData.make(lake, LocationPrecision.HUNDRED_METERS, "Dhanmondi 27", "Lake gate")
        val tag = card.toTag()
        assertEquals(listOf("card", "location"), tag.take(2))
        assertEquals("100m", tag[4])
        assertEquals("Lake gate", tag[5])
        assertEquals(card, LocationCardData.fromTags(listOf(listOf("p", "abc"), tag)))
        assertTrue(card.shortPlusCode().endsWith(" Dhaka"))
        assertTrue(card.shareText().contains("openstreetmap.org/?mlat=${tag[2]}&mlon=${tag[3]}"))
        assertNull(LocationCardData.fromTag(listOf("card", "location", "91", "90", "exact", "", "")))
        assertNull(LocationCardData.fromTag(listOf("card", "location", "23.7", "90.3", "street", "", "")))
        assertNull(LocationCardData.fromTag(listOf("card", "location", "23.7", "90.3", "exact", "", "NOTACODE")))
        assertNull(LocationCardData.fromTag(listOf("card", "task", "23.7", "90.3", "exact", "", "")))
    }
}
