package io.github.tanzir71.harkara.core.location

import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.UnsignedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Gate for M9: live-share replay protection. */
class LiveShareTest {
    private var clock = 1_764_000_000L
    private val ring = KeyRing(ByteArray(32) { 7 })
    private val rafi = ring.profile()
    private val session = ring.session("share-1")
    private val me = KeyPair.generate()
    private val end = clock + LiveDuration.HOUR_1.seconds
    private val tracker = LiveShareTracker { clock }
    private val here = LatLng(23.7465, 90.3760)

    private fun start(owner: KeyPair = rafi, key: KeyPair = session, shareId: String = "share-1", e: Long = end) =
        LiveShares.startRumor(owner, key, shareId, listOf(me.pubkeyHex), e, clock, "Rafi")

    private fun point(seq: Long, at: LatLng? = here, key: KeyPair = session, e: Long = end, shareId: String = "share-1", stopped: Boolean = false) =
        LiveShares.pointRumor(key, shareId, seq, e, at, 12, clock, stopped)

    @Test
    fun `points pass only in order, after a bound start, before the end`() {
        assertEquals(LiveVerdict.UNKNOWN_SESSION, tracker.accept(point(1)))
        assertEquals(LiveVerdict.STARTED, tracker.accept(start()))
        assertEquals(LiveVerdict.ACCEPTED, tracker.accept(point(1)))
        clock += 30
        assertEquals(LiveVerdict.ACCEPTED, tracker.accept(point(2, LatLng(23.7470, 90.3765))))
        assertEquals(LiveVerdict.REPLAY, tracker.accept(point(2, LatLng(23.0, 90.0))))
        assertEquals(LiveVerdict.REPLAY, tracker.accept(point(1, LatLng(23.0, 90.0))))
        assertEquals(LiveVerdict.ACCEPTED, tracker.accept(point(5)))
        assertEquals(5L, tracker.lastSeq(session.pubkeyHex))
        val person = tracker.active().single()
        assertEquals(rafi.pubkeyHex, person.owner)
        assertEquals("Rafi", person.name)
        assertEquals(here, person.position)
        assertEquals(12, person.accuracyMeters)

        clock = end + 1
        assertEquals(LiveVerdict.ENDED, tracker.accept(point(6)))
        assertTrue(tracker.active().isEmpty(), "the share stops by itself")
    }

    @Test
    fun `points written after the end or claiming another end are dropped`() {
        tracker.accept(start())
        val late = UnsignedEvent(session.pubkeyHex, end + 5, Kinds.LIVE_LOCATION, listOf(listOf("share", "share-1"), listOf("seq", "3"), listOf("end", end.toString())), """{"lat":23.7,"lng":90.3}""")
        assertEquals(LiveVerdict.ENDED, tracker.accept(late))
        assertEquals(LiveVerdict.MISMATCH, tracker.accept(point(4, e = end + 3600)))
        assertEquals(LiveVerdict.MISMATCH, tracker.accept(point(4, shareId = "share-2")))
        assertEquals(LiveVerdict.BAD_POINT, tracker.accept(LiveShares.pointRumor(session, "share-1", 7, end, null, null, clock)))
    }

    @Test
    fun `foreign and unbound session keys are refused`() {
        val mallory = KeyPair.generate()
        // Mallory claims Rafi's session key: the binding signature doesn't verify for her.
        val forged = UnsignedEvent(mallory.pubkeyHex, clock, Kinds.LIVE_LOCATION, start().tags, "")
        assertEquals(LiveVerdict.BAD_START, tracker.accept(forged))
        assertEquals(LiveVerdict.UNKNOWN_SESSION, tracker.accept(point(1)))
        // Points from a key nobody started are dropped.
        assertEquals(LiveVerdict.STARTED, tracker.accept(start()))
        assertEquals(LiveVerdict.UNKNOWN_SESSION, tracker.accept(point(1, key = KeyPair.generate())))
        // A start for an over-long share is refused outright.
        assertThrows<IllegalArgumentException> { start(e = clock + 9 * 3600) }
        val tooLong = UnsignedEvent(rafi.pubkeyHex, clock, Kinds.LIVE_LOCATION, start().tags.map { if (it[0] == "end") listOf("end", (clock + 9 * 3600).toString()) else it }, "")
        assertEquals(LiveVerdict.BAD_START, tracker.accept(tooLong))
        // Replaying the same start doesn't reset the sequence.
        assertEquals(LiveVerdict.ACCEPTED, tracker.accept(point(3)))
        assertEquals(LiveVerdict.REPLAY, tracker.accept(start()))
        assertEquals(LiveVerdict.REPLAY, tracker.accept(point(2)))
        assertEquals(LiveVerdict.NOT_LIVE, tracker.accept(UnsignedEvent(rafi.pubkeyHex, clock, Kinds.CHAT_MESSAGE, emptyList(), "hi")))
    }

    @Test
    fun `stop ends the share, even when the stop arrives before a re-sent start`() {
        tracker.accept(start())
        tracker.accept(point(1))
        assertEquals(LiveVerdict.STOPPED, tracker.accept(point(2, at = null, stopped = true)))
        assertTrue(tracker.active().isEmpty())
        assertEquals(LiveVerdict.ENDED, tracker.accept(point(3)))
        assertEquals(LiveVerdict.ENDED, tracker.accept(start()))

        val fresh = LiveShareTracker { clock }
        assertEquals(LiveVerdict.STOPPED, fresh.accept(point(9, at = null, stopped = true)))
        assertEquals(LiveVerdict.ENDED, fresh.accept(start()))
        assertTrue(fresh.active().isEmpty())
    }

    @Test
    fun `wraps - start is a stored gift wrap that expires, points are ephemeral and open only for us`() {
        val starts = LiveShares.startWraps(rafi, session, "share-1", listOf(me.pubkeyHex), end, clock, "Rafi")
        assertEquals(Kinds.GIFT_WRAP, starts.single().kind)
        assertEquals(end.toString(), starts.single().tagValue("expiration"))
        val opened = GiftWrap.unwrap(starts.single(), me)
        assertEquals(rafi.pubkeyHex, opened.pubkey)
        assertEquals(LiveVerdict.STARTED, tracker.accept(opened))

        val wraps = LiveShares.pointWraps(session, listOf(me.pubkeyHex), point(1))
        assertEquals(Kinds.EPHEMERAL_GIFT_WRAP, wraps.single().kind)
        assertTrue(!wraps.single().content.contains("23.7"))
        val pt = GiftWrap.unwrap(wraps.single(), me)
        assertEquals(session.pubkeyHex, pt.pubkey)
        assertEquals(LiveVerdict.ACCEPTED, tracker.accept(pt))
        assertTrue(runCatching { GiftWrap.unwrap(wraps.single(), rafi) }.isFailure)
        assertEquals(ring.session("share-1"), session)
    }
}
