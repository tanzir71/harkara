package io.github.tanzir71.harkara.core.mesh

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.CarrierLadder
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.TestData
import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.store.EventStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The mesh engine over pretend radios: hops, fragments, digests, bridge distance, mesh rules. */
class MeshEngineTest {
    private val now = Instant.ofEpochSecond(TestData.NOW)
    private val air = FakeRadioAir(mtu = 120)
    private val phones = mutableListOf<Phone>()

    inner class Phone(val name: String, profile: MeshProfile = MeshProfile.EVENT) : MeshEngine.Listener {
        val received = CopyOnWriteArrayList<Event>()
        val hops = ConcurrentHashMap<String, Int>()
        val engine = MeshEngine(EventStore(setOf("wh0r")), { ByteArray(16) { (name.hashCode() + it).toByte() } }, { now }, profile, this)

        override fun onEvent(event: Event, hops: Int, peer: String) {
            received += event
            this.hops[event.id] = hops
        }

        fun up() = apply { engine.attach(air.radio(name)) }
    }

    private fun phone(name: String, profile: MeshProfile = MeshProfile.EVENT) = Phone(name, profile).also { phones += it }

    private fun line(vararg names: String): List<Phone> {
        val list = names.map { phone(it).up() }
        names.toList().zipWithNext().forEach { (a, b) -> air.link(a, b) }
        air.awaitIdle()
        return list
    }

    @AfterEach
    fun tearDown() {
        phones.forEach { it.engine.stop() }
        air.shutdown()
    }

    @Test
    fun `an event crosses three hops over small links, fragmented and verified`() {
        val (a, _, _, d) = line("a", "b", "c", "d")
        val post = TestData.post(TestData.keys(1), text = "Water is knee deep at Road 7, cars turning back. ".repeat(3))

        assertTrue(a.engine.originate(post))
        air.awaitIdle()

        assertEquals(listOf(post.id), d.received.map { it.id })
        assertEquals(3, d.hops[post.id])
        assertTrue(d.received.single().verify())
        assertFalse(a.engine.originate(post), "the same event is carried once")
    }

    @Test
    fun `a phone that links later pulls what it missed through the digest`() {
        val a = phone("a").up()
        val b = phone("b").up()
        val post = TestData.post(TestData.keys(2))
        a.engine.originate(post)
        air.awaitIdle()
        assertTrue(b.received.isEmpty())

        air.link("a", "b")
        air.awaitIdle()
        assertEquals(listOf(post.id), b.received.map { it.id })
    }

    @Test
    fun `bridge distance spreads hop by hop and disappears when the bridge goes`() {
        val (a, b, c, d) = line("a", "b", "c", "d")
        assertNull(a.engine.bridgeHops)

        d.engine.hasInternet = true
        air.awaitIdle()
        assertEquals(0, d.engine.bridgeHops)
        assertEquals(1, c.engine.bridgeHops)
        assertEquals(2, b.engine.bridgeHops)
        assertEquals(3, a.engine.bridgeHops)
        assertEquals(2, a.engine.peers().single().bridgeHops)

        air.unlink("c", "d")
        air.awaitIdle()
        assertNull(c.engine.bridgeHops)
        assertNull(a.engine.bridgeHops)
    }

    @Test
    fun `carried events reach offline neighbours, pushed ones at once`() {
        val (a, b) = line("a", "b")
        val sos = TestData.keys(3).signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0d")), "Need help at Road 7", TestData.NOW)
        val post = TestData.post(TestData.keys(4))

        assertTrue(a.engine.carry(sos, push = true))
        assertTrue(a.engine.carry(post))
        air.awaitIdle()
        assertEquals(listOf(sos.id), b.received.map { it.id })
        assertEquals(setOf(sos.id, post.id), a.engine.carried().map { it.id }.toSet())

        val c = phone("c").up()
        air.link("a", "c")
        air.awaitIdle()
        assertEquals(setOf(sos.id, post.id), c.received.map { it.id }.toSet())
    }

    @Test
    fun `sos frames are written at top priority`() {
        val (a) = line("a", "b")
        air.priorities.clear()
        a.engine.originate(TestData.keys(5).signEvent(Kinds.SOS, listOf(listOf("g", "wh0r0d")), "SOS", TestData.NOW))
        air.awaitIdle()
        assertEquals(MeshPriority.SOS, air.priorities.first())
    }

    @Test
    fun `photos and wifi-room messages never go over the mesh`() {
        val a = phone("a").up()
        val photo = TestData.keys(6).signEvent(
            Kinds.FEED_POST,
            listOf(listOf("g", "wh0r0d"), listOf("t", "stock"), listOf("imeta", "url https://example.org/x.jpg")),
            "Rice at the corner shop",
            TestData.NOW,
        )
        val wifi = ChatProtocol.wifiMessage(TestData.keys(7), "Anyone on this Wi-Fi?", TestData.NOW)
        val expired = TestData.post(TestData.keys(8), createdAt = TestData.NOW - 10_000, expiresAt = TestData.NOW - 1)
        assertFalse(a.engine.originate(photo))
        assertFalse(a.engine.originate(wifi))
        assertFalse(a.engine.originate(expired))
    }

    @Test
    fun `saver mode scans in windows, event mode all the time`() {
        assertTrue(MeshProfile.SAVER.scanning(0))
        assertTrue(MeshProfile.SAVER.scanning(9))
        assertFalse(MeshProfile.SAVER.scanning(10))
        assertFalse(MeshProfile.SAVER.scanning(59))
        assertTrue(MeshProfile.SAVER.scanning(60))
        assertTrue((0L..600L).all { MeshProfile.EVENT.scanning(it) })
        assertTrue(MeshProfile.EVENT.ttl > MeshProfile.SAVER.ttl)
    }

    @Test
    fun `presence round trips and old 16-byte presence still reads`() {
        val id = ByteArray(16) { it.toByte() }
        val info = PresenceInfo(id, internet = false, node = true, bridgeHops = 2)
        assertEquals(info, PresenceInfo.parse(info.frame()))
        assertEquals(PresenceInfo(id), PresenceInfo.parse(io.github.tanzir71.harkara.core.wire.MeshFrame.presence(id)))
    }

    @Test
    fun `only the smaller token dials, within the link limit`() {
        val low = byteArrayOf(1, 2, 3, 4)
        val high = byteArrayOf(1, 2, 3, 5)
        assertTrue(LinkPolicy.shouldDial(low, high, links = 0, maxLinks = 4, alreadyLinked = false))
        assertFalse(LinkPolicy.shouldDial(high, low, links = 0, maxLinks = 4, alreadyLinked = false))
        assertFalse(LinkPolicy.shouldDial(low, high, links = 4, maxLinks = 4, alreadyLinked = false))
        assertFalse(LinkPolicy.shouldDial(low, high, links = 0, maxLinks = 4, alreadyLinked = true))
        assertFalse(LinkPolicy.shouldDial(low, null, links = 0, maxLinks = 4, alreadyLinked = false))
        assertFalse(LinkPolicy.shouldDial(low, low.copyOf(), links = 0, maxLinks = 4, alreadyLinked = false))
    }

    @Test
    fun `rough range from signal strength`() {
        assertEquals(1.0, RangeEstimate.meters(-59), 0.01)
        assertTrue(RangeEstimate.meters(-84) in 9.0..11.0)
        assertEquals(4, RangeEstimate.bars(-50))
        assertEquals(0, RangeEstimate.bars(-95))
    }

    @Test
    fun `the ladder names automatic carriers best first and the pill tells the truth`() {
        val nothing = ConnectivityState()
        assertEquals(emptyList<Carrier>(), CarrierLadder.automatic(nothing, meshEligible = true))
        assertEquals(Carrier.SAVED, nothing.copy(available = CarrierLadder.available(nothing)).best)

        val meshOnly = ConnectivityState(meshOn = true, meshPeers = 2)
        assertEquals(listOf(Carrier.MESH), CarrierLadder.automatic(meshOnly, meshEligible = true))
        assertEquals(emptyList<Carrier>(), CarrierLadder.automatic(meshOnly, meshEligible = false))
        assertEquals(Carrier.MESH, meshOnly.copy(available = CarrierLadder.available(meshOnly)).best)

        val bridged = meshOnly.copy(bridgeHops = 2)
        assertEquals(listOf(Carrier.BRIDGE), CarrierLadder.automatic(bridged, meshEligible = true))
        assertEquals(Carrier.BRIDGE, bridged.copy(available = CarrierLadder.available(bridged)).best)

        val everything = bridged.copy(relaysReachable = 3, nodePeers = 1)
        assertEquals(listOf(Carrier.ONLINE, Carrier.NODE, Carrier.BRIDGE), CarrierLadder.automatic(everything, meshEligible = true))
        assertEquals(Carrier.ONLINE, everything.copy(available = CarrierLadder.available(everything)).best)

        val meshOff = ConnectivityState(meshOn = false, meshPeers = 3)
        assertFalse(Carrier.MESH in CarrierLadder.available(meshOff))
    }
}
