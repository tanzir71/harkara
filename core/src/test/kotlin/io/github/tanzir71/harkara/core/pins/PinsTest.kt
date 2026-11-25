package io.github.tanzir71.harkara.core.pins

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PinsTest {
    private val owner = KeyPair.generate()
    private val friend = KeyPair.generate()
    private val stranger = KeyPair.generate()
    private val now = 1_764_000_000L
    private val spot = LatLng(23.74651, 90.37602)

    private fun pin(visibility: PinVisibility, title: String = "Water point", at: Long = now, recipients: List<String> = emptyList()) = Pin(
        id = "pin-1", owner = owner.pubkeyHex, visibility = visibility, title = title,
        note = "Tubewell behind the lake mosque, open 7:00 to 10:00", position = spot, color = Category.STOCK,
        createdAt = at, expiresAt = if (visibility == PinVisibility.PUBLIC) at + Pins.DEFAULT_PUBLIC_TTL else null, recipients = recipients,
    )

    @Test
    fun `private pin is NIP-78 app data only its owner can read`() {
        val event = Pins.privateEvent(owner, pin(PinVisibility.PRIVATE))
        assertEquals(Kinds.APP_DATA, event.kind)
        assertEquals(listOf(listOf("d", "pin-1")), event.tags)
        assertFalse(event.content.contains("Water"))
        assertTrue(event.verify())
        val read = Pins.parsePrivate(event, owner)!!
        assertEquals("Water point", read.title)
        assertEquals(spot.lat, read.position.lat, 1e-6)
        assertEquals("30078:${owner.pubkeyHex}:pin-1", read.address)
        assertNull(Pins.parsePrivate(event, stranger))
        assertNull(Pins.parsePrivate(event.copy(content = event.content.reversed()), owner))
    }

    @Test
    fun `public pin carries d, title, g cells and expiry`() {
        val event = Pins.publicEvent(owner, pin(PinVisibility.PUBLIC))
        assertEquals(Kinds.PIN, event.kind)
        assertEquals("pin-1", event.tagValue("d"))
        assertEquals("Water point", event.tagValue("title"))
        assertEquals(listOf(9, 6, 5), event.tagValues("g").map { it.length })
        assertEquals((now + Pins.DEFAULT_PUBLIC_TTL).toString(), event.tagValue("expiration"))
        val read = Pins.parsePublic(event, now)!!
        assertTrue(GeoMath.distanceMeters(spot, read.position) < 5)
        assertEquals(PinVisibility.PUBLIC, read.visibility)
        assertNull(Pins.parsePublic(event, now + Pins.DEFAULT_PUBLIC_TTL), "expired pins are dropped")
        assertNull(Pins.parsePublic(event.copy(content = "tampered"), now), "bad signatures are dropped")
        assertThrows<IllegalArgumentException> { Pins.publicEvent(stranger, pin(PinVisibility.PUBLIC)) }
    }

    @Test
    fun `shared pin is a gift-wrapped rumor for the named people and the owner`() {
        val shared = pin(PinVisibility.SHARED, recipients = listOf(friend.pubkeyHex))
        val wraps = Pins.sharedWraps(owner, shared)
        assertEquals(2, wraps.size)
        assertTrue(wraps.all { it.kind == Kinds.GIFT_WRAP && !it.content.contains("Water") })
        val atFriend = wraps.firstNotNullOf { w -> runCatching { GiftWrap.unwrap(w, friend) }.getOrNull() }
        val read = Pins.parseShared(atFriend, now)!!
        assertEquals(owner.pubkeyHex, read.owner)
        assertEquals(listOf(friend.pubkeyHex), read.recipients)
        assertEquals("33471:${owner.pubkeyHex}:pin-1", read.address)
        assertTrue(wraps.none { w -> runCatching { GiftWrap.unwrap(w, stranger) }.isSuccess })
        // A shared pin published openly is not taken for a public one.
        val rumor = Pins.sharedRumor(owner, shared)
        assertNull(Pins.parsePublic(owner.signEvent(Kinds.PIN, rumor.tags, rumor.content, now), now))
    }

    @Test
    fun `newest version wins, ties go to the lower id, deletions hide the pin`() {
        val v1 = Pins.parsePublic(Pins.publicEvent(owner, pin(PinVisibility.PUBLIC, "Water point", now)), now)!!
        val v2 = Pins.parsePublic(Pins.publicEvent(owner, pin(PinVisibility.PUBLIC, "Water point (dry)", now + 60)), now + 60)!!
        assertEquals("Water point (dry)", Pins.latest(listOf(v2, v1), now + 60).single().title)
        assertEquals("Water point (dry)", Pins.latest(listOf(v1, v2), now + 60).single().title)
        val tieA = v1.copy(title = "A", eventId = "00aa")
        val tieB = v1.copy(title = "B", eventId = "00bb")
        assertEquals("A", Pins.newer(tieB, tieA).title)
        val gone = Pins.parsePublic(Pins.publicEvent(owner, pin(PinVisibility.PUBLIC, at = now + 120).copy(deleted = true), now + 120), now + 120)!!
        assertTrue(gone.deleted)
        assertTrue(Pins.latest(listOf(v1, v2, gone), now + 120).isEmpty())
        val other = v1.copy(owner = stranger.pubkeyHex)
        assertEquals(2, Pins.latest(listOf(v1, other), now).size)
    }

    @Test
    fun `still there tally counts each key's latest answer in the last week`() {
        val p = pin(PinVisibility.PUBLIC)
        val voters = List(6) { KeyPair.generate() }
        val reactions = buildList {
            voters.take(4).forEach { add(Pins.stillThere(it, p, true, now + 10)) }
            add(Pins.stillThere(voters[4], p, true, now + 10))
            add(Pins.stillThere(voters[4], p, false, now + 20)) // changed their mind
            add(Pins.stillThere(voters[5], p, false, now - Pins.VOTE_WINDOW - 5)) // too old
            add(Pins.stillThere(voters[5], p.copy(id = "other"), false, now + 10)) // another pin
        }
        val tally = Pins.tally(p, reactions, now + 30, myKeys = setOf(voters[4].pubkeyHex))
        assertEquals(4, tally.yes)
        assertEquals(1, tally.no)
        assertEquals(false, tally.mine)
        assertEquals(listOf("a", p.address), reactions.first().tags.first())
        assertNotNull(Pins.answer(reactions.first(), p))
    }

    @Test
    fun `gpx, kml and geojson round trip`() {
        val pins = listOf(
            pin(PinVisibility.PRIVATE, "Water point & tap <north>"),
            pin(PinVisibility.PUBLIC, "ধানমন্ডি লেক গেট").copy(id = "pin-2", position = LatLng(23.7503, 90.3791), note = ""),
        )
        for (format in PinFileFormat.entries) {
            val text = PinFiles.export(pins, format)
            val back = PinFiles.import(text, format)
            assertEquals(pins.map { it.title }, back.map { it.title }, format.name)
            assertEquals(pins[0].note, back[0].note, format.name)
            back.zip(pins).forEach { (b, p) ->
                assertEquals(p.position.lat, b.position.lat, 1e-6)
                assertEquals(p.position.lng, b.position.lng, 1e-6)
            }
            assertEquals(format, PinFileFormat.guess("pins." + format.extension, text))
            assertEquals(format, PinFileFormat.guess("download", text))
        }
    }

    @Test
    fun `foreign files - other apps' shapes, tracks and hostile XML`() {
        val osmand = """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><wpt lat="23.81" lon="90.41"><name>Gulshan 1</name><cmt>circle</cmt></wpt><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk><wpt lat="99" lon="0"><name>bad</name></wpt></gpx>"""
        assertEquals(listOf("Gulshan 1"), PinFiles.import(osmand, PinFileFormat.GPX).map { it.title })
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>Shelter</name><Point><coordinates> 90.40,23.70,0 </coordinates></Point></Placemark><Placemark><name>Road</name><LineString><coordinates>90,23 91,24</coordinates></LineString></Placemark></Document></kml>"""
        assertEquals(listOf(ImportedPlace("Shelter", "", LatLng(23.70, 90.40))), PinFiles.import(kml, PinFileFormat.KML))
        val geo = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[90.41,23.79]},"properties":{"title":"Clinic"}},{"type":"Feature","geometry":{"type":"Polygon","coordinates":[]},"properties":{}},{"type":"Feature","geometry":{"type":"Point","coordinates":[90.42,23.78]},"properties":null}]}"""
        assertEquals(listOf("Clinic", "23.78000, 90.42000"), PinFiles.import(geo, PinFileFormat.GEOJSON).map { it.title })
        val xxe = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx><wpt lat="1" lon="1"><name>&x;</name></wpt></gpx>"""
        assertTrue(PinFiles.import(xxe, PinFileFormat.GPX).isEmpty())
        assertTrue(PinFiles.import("not json", PinFileFormat.GEOJSON).isEmpty())
        assertTrue(PinFiles.import("<gpx", PinFileFormat.GPX).isEmpty())
    }
}
