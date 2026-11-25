package io.github.tanzir71.harkara.core.chat

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.geo.OpenLocationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatCardsTest {
    private val pharmacy = LatLng(23.746512, 90.376045)

    @Test
    fun `every card round-trips through its tag`() {
        val cards = listOf(
            LocationShare.card(pharmacy.lat, pharmacy.lng, LocationPrecision.APPROX, "On my way", "Road 8 pharmacy"),
            ChatCard.Task("Task · Pickup", "৳120", "Two insulin pens from the Road 8 pharmacy to House 12", 1, "Accepted 21:16", "Sumon · 38 done"),
            ChatCard.Iou("৳500", lent = true, what = "Lunch at Star Kabab", status = "Waiting for Rafi to sign", date = "Today", agreed = false),
            ChatCard.CheckIn("I'm safe", "Home · 21:40"),
            ChatCard.Pin("Water point", "Tubewell behind the lake mosque", "Public", 4, 1, "stock"),
        )
        cards.forEach { card ->
            val tags = listOf(listOf("p", "ab"), card.toTag())
            assertEquals(card, ChatCard.from(tags))
        }
    }

    @Test
    fun `malformed cards are ignored`() {
        assertNull(ChatCard.parse(listOf("card", "location", "abc", "90", "exact", "x", "y")))
        assertNull(ChatCard.parse(listOf("card", "location", "95.0", "90.0", "exact", "x", "y")))
        assertNull(ChatCard.parse(listOf("card", "location", "23.7", "90.3", "street", "x", "y")))
        assertNull(ChatCard.parse(listOf("card", "rocket", "1")))
        assertNull(ChatCard.parse(listOf("card", "task", "a", "b", "c", "not a number", "e")))
        assertNull(ChatCard.from(listOf(listOf("p", "x"))))
    }

    @Test
    fun `location precision coarsens the shared point`() {
        val exact = LocationShare.card(pharmacy.lat, pharmacy.lng, LocationPrecision.EXACT, "Here")
        assertEquals(23.74651, exact.lat, 1e-9)
        assertEquals(11, exact.plusCode.length)
        assertTrue(GeoMath.distanceMeters(pharmacy, LatLng(exact.lat, exact.lng)) < 2)

        val approx = LocationShare.card(pharmacy.lat, pharmacy.lng, LocationPrecision.APPROX, "Near")
        assertEquals(23.747, approx.lat, 1e-9)
        assertEquals(90.376, approx.lng, 1e-9)
        val off = GeoMath.distanceMeters(pharmacy, LatLng(approx.lat, approx.lng))
        assertTrue(off in 1.0..100.0, "about 100 m, was $off")

        val area = LocationShare.card(pharmacy.lat, pharmacy.lng, LocationPrecision.AREA, "Around")
        assertEquals(GeoHash.encode(pharmacy.lat, pharmacy.lng, 5), GeoHash.encode(area.lat, area.lng, 5))
        assertTrue(area.plusCode.endsWith("00+"))
        assertTrue(OpenLocationCode.isFull(area.plusCode))
    }

    @Test
    fun `plus codes near Dhaka are shown short with the city`() {
        val code = LocationShare.plusCode(23.747, 90.376, LocationPrecision.APPROX)
        assertTrue(code.startsWith("7MMG"))
        assertEquals(code.substring(4) + " Dhaka", LocationShare.displayCode(code))
        val chittagong = LocationShare.plusCode(22.3569, 91.7832, LocationPrecision.APPROX)
        assertEquals(chittagong, LocationShare.displayCode(chittagong))
        assertEquals("7MMG7M00+", LocationShare.displayCode("7MMG7M00+"))
    }
}
