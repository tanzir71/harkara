package io.github.tanzir71.harkara.core.relay

import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.signEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RelayMessagesTest {
    private val keys = KeyPair.generate()
    private val event = keys.signEvent(1, listOf(listOf("g", "wh0r3"), listOf("t", "traffic")), "Jam at Nilkhet", 1_764_000_000)

    @Test
    fun `client messages round trip`() {
        val req = ClientMessage.Req("feed", listOf(Filter(kinds = listOf(1), tags = mapOf("g" to listOf("wh0r3")), since = 10, limit = 50)))
        assertEquals(req, ClientMessage.parse(req.toJson()))
        assertEquals(ClientMessage.Publish(event), ClientMessage.parse(ClientMessage.Publish(event).toJson()))
        assertEquals(ClientMessage.Close("feed"), ClientMessage.parse(ClientMessage.Close("feed").toJson()))
        assertNull(ClientMessage.parse("[\"NOPE\"]"))
    }

    @Test
    fun `relay messages parse and round trip, garbage is ignored`() {
        listOf(
            RelayMessage.EventMessage("feed", event),
            RelayMessage.Ok(event.id, true, ""),
            RelayMessage.Ok(event.id, false, "blocked: rate limit"),
            RelayMessage.Eose("feed"),
            RelayMessage.Closed("feed", "error: too many"),
            RelayMessage.Notice("hello"),
        ).forEach { assertEquals(it, RelayMessage.parse(it.toJson())) }
        assertNull(RelayMessage.parse("not json"))
        assertNull(RelayMessage.parse("[\"EVENT\",\"s\",{\"id\":1}]"))
    }

    @Test
    fun `filters match like NIP-01`() {
        assertTrue(Filter(kinds = listOf(1), tags = mapOf("g" to listOf("wh0r3", "x"))).matches(event))
        assertFalse(Filter(kinds = listOf(7)).matches(event))
        assertFalse(Filter(tags = mapOf("t" to listOf("food"))).matches(event))
        assertFalse(Filter(since = event.createdAt + 1).matches(event))
        assertTrue(Filter(authors = listOf(keys.pubkeyHex), ids = listOf(event.id)).matches(event))
    }

    @Test
    fun `nip11 limits are parsed`() {
        val info = RelayInfo.parse(
            """{"name":"r","supported_nips":[1,11,40,77],"limitation":{"max_message_length":16384,"max_subscriptions":20,"auth_required":false}}""",
        )
        assertTrue(info.supportsNegentropy)
        assertEquals(20, info.maxSubscriptions)
        assertTrue(info.accepts(1000))
        assertFalse(info.accepts(16_380))
        assertEquals(RelayInfo.Unknown, RelayInfo.parse("garbage"))
    }

    @Test
    fun `backoff grows, is jittered and capped`() {
        val backoff = Backoff(baseMs = 1000, maxMs = 8000, random = Random(1))
        val delays = (1..8).map { backoff.nextDelayMs() }
        assertTrue(delays[0] in 500..1000)
        assertTrue(delays.last() in 4000..8000)
        backoff.reset()
        assertTrue(backoff.nextDelayMs() <= 1000)
    }

    @Test
    fun `geohash neighbours and distances`() {
        val gh = GeoHash.encode(23.7465, 90.376, 5)
        val cells = GeoMath.withNeighbours(gh)
        assertEquals(9, cells.size)
        assertTrue(gh in cells)
        val centre = GeoMath.decode(gh)
        assertTrue(GeoMath.distanceMeters(centre, LatLng(23.7465, 90.376)) < 5000)
        assertEquals(1, GeoMath.distanceMeters(LatLng(23.7465, 90.376), LatLng(23.7375, 90.376)).toInt() / 1000)
        assertEquals("350 m", GeoMath.distanceLabel(355.0))
        assertEquals("1.1 km", GeoMath.distanceLabel(1100.0))
    }
}
