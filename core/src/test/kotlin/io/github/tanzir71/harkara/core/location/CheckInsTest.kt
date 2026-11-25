package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CheckInsTest {
    private val nabila = KeyPair.generate()
    private val mum = KeyPair.generate()
    private val friend = KeyPair.generate()
    private val stranger = KeyPair.generate()
    private val t0 = 1_764_000_000L

    @Test
    fun `check-in is a sealed rumor wrapped to the chosen people and the sender`() {
        val rumor = CheckIns.rumor(nabila, CheckInStatus.SAFE, "Home", listOf(mum.pubkeyHex, friend.pubkeyHex), t0, "wh0r0", note = "Reached, all fine")
        val wraps = CheckIns.wraps(nabila, rumor)
        assertEquals(3, wraps.size)
        assertTrue(wraps.all { it.kind == Kinds.GIFT_WRAP && it.verify() && !it.content.contains("Home") })
        for (reader in listOf(mum, friend, nabila)) {
            val opened = wraps.firstNotNullOf { runCatching { GiftWrap.unwrap(it, reader) }.getOrNull() }
            val c = CheckIns.parse(opened)!!
            assertEquals(nabila.pubkeyHex, c.author)
            assertEquals(CheckInStatus.SAFE, c.status)
            assertEquals("Home", c.place)
            assertEquals("wh0r0", c.geohash)
            assertEquals("Reached, all fine", c.note)
            assertEquals(rumor.id, c.id)
        }
        assertTrue(wraps.none { w -> runCatching { GiftWrap.unwrap(w, stranger) }.isSuccess })
        assertThrows<IllegalArgumentException> { CheckIns.rumor(nabila, CheckInStatus.SAFE, "Home", emptyList(), t0) }
    }

    @Test
    fun `a forged check-in does not verify`() {
        val rumor = CheckIns.rumor(nabila, CheckInStatus.SAFE, "Home", listOf(mum.pubkeyHex), t0)
        // Someone else seals Nabila's rumor: the unwrap refuses it (seal signer must be the author).
        val sealedByStranger = runCatching { GiftWrap.seal(rumor, stranger, mum.pubkeyHex) }
        assertTrue(sealedByStranger.isFailure)
        val impostor = UnsignedEvent(nabila.pubkeyHex, t0, Kinds.CHECK_IN, rumor.tags, "")
        val wrap = GiftWrap.wrap(GiftWrap.seal(UnsignedEvent(stranger.pubkeyHex, t0, Kinds.CHECK_IN, impostor.tags, ""), stranger, mum.pubkeyHex), mum.pubkeyHex)
        assertEquals(stranger.pubkeyHex, CheckIns.parse(GiftWrap.unwrap(wrap, mum))!!.author)
        assertNull(CheckIns.parse(UnsignedEvent(nabila.pubkeyHex, t0, Kinds.CHECK_IN, listOf(listOf("status", "lost")), "")))
        assertNull(CheckIns.parse(UnsignedEvent(nabila.pubkeyHex, t0, Kinds.CHECK_IN, listOf(listOf("status", "overdue"), listOf("place", "x")), "")))
    }

    @Test
    fun `overdue only when no safe check-in since the trip started`() {
        val trip = TripPlan("trip-1", "Banani 11", due = t0 + 3600, watchers = listOf(mum.pubkeyHex), startedAt = t0)
        fun safe(at: Long, tripId: String? = "trip-1") = CheckIns.parse(CheckIns.rumor(nabila, CheckInStatus.SAFE, "Banani 11", listOf(mum.pubkeyHex), at, tripId = tripId))!!

        assertFalse(TripRules.isOverdue(trip, emptyList(), t0 + 3599), "not before the deadline")
        assertTrue(TripRules.isOverdue(trip, emptyList(), t0 + 3600))
        assertFalse(TripRules.isOverdue(trip, listOf(safe(t0 + 1800)), t0 + 3600))
        assertFalse(TripRules.isOverdue(trip, listOf(safe(t0 + 1800, tripId = null)), t0 + 4000))
        assertTrue(TripRules.isOverdue(trip, listOf(safe(t0 - 60)), t0 + 3600), "a check-in from before the trip doesn't count")
        assertTrue(TripRules.isOverdue(trip, listOf(safe(t0 + 60, tripId = "trip-0")), t0 + 3600), "nor one for another trip")
        assertFalse(TripRules.isOverdue(trip.copy(status = TripStatus.CANCELLED), emptyList(), t0 + 7200))

        val alert = CheckIns.parse(TripRules.overdueRumor(nabila, trip, "Mohakhali", t0 + 3600))!!
        assertEquals(CheckInStatus.OVERDUE, alert.status)
        assertEquals("Banani 11", alert.destination)
        assertEquals(t0 + 3600, alert.due)
        assertEquals(listOf(mum.pubkeyHex), alert.recipients)
        val plan = CheckIns.parse(TripRules.startRumor(nabila, trip, "Dhanmondi"))!!
        assertEquals(CheckInStatus.TRIP, plan.status)
        assertEquals("trip-1", plan.tripId)
    }
}
