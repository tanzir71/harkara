package io.github.tanzir71.harkara.places

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.location.CheckIn
import io.github.tanzir71.harkara.core.location.CheckInStatus
import io.github.tanzir71.harkara.core.location.LiveDuration
import io.github.tanzir71.harkara.core.location.TripStatus
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.lan.FakeLanNetwork
import io.github.tanzir71.harkara.lan.LanRoom
import io.github.tanzir71.harkara.lan.NodeController
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Private place/safety envelopes traverse ordinary Wi-Fi rooms, with no relay or mesh fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlacesLanIntegrationTest {
    private val clock = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = FakeLanNetwork("127.0.0.1")
    private val phones = mutableListOf<Phone>()
    private val lake = LatLng(23.7465, 90.3760)

    private inner class Phone(name: String) {
        val ring = KeyRing(KeyRing.generateSeed(SecureRandom()))
        val keys = ring.profile()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        // Never call setRelays on this pool: the room owns a separate, loopback-only pool.
        val pool = RelayPool(scope, now = clock::get)
        val room = LanRoom(scope, wifi.discovery(), InetAddress.getByName("127.0.0.1"), now = clock::get)
        val inbox = GiftInbox({ keys }, clock::get)
        val store = RoomEventStore(db, clock::get)
        val receivedEphemeral = CopyOnWriteArrayList<Event>()
        val node = NodeController(
            scope, room, db, store, pool,
            site = { error("These phones never enable node or sharing mode") },
            clock = clock::get,
            ingest = { event, carrier ->
                if (event.validate() && !event.isExpired(clock.get() / 1000)) {
                    val handled = inbox.offer(event) != null
                    if (handled) {
                        if (event.kind == Kinds.EPHEMERAL_GIFT_WRAP) receivedEphemeral += event
                        else store.store(event, carrier, own = false)
                    }
                    handled
                } else {
                    false
                }
            },
        )
        val outbox = Outbox(db, pool, clock::get, routes = listOf(node.envelopeRoute))
        private val areaKeys = mutableMapOf<String, KeyPair>()
        val pins = PinRepository(db, outbox, pool, { keys }, { area ->
            synchronized(areaKeys) { areaKeys.getOrPut(area) { KeyPair.generate() } }
        }, clock::get)
        private val scheduler = object : OverdueScheduler {
            override fun schedule(tripId: String, atMillis: Long, again: Boolean) = Unit
            override fun cancel(tripId: String) = Unit
        }
        val checkIns = CheckInRepository(db, outbox, { keys }, scheduler, clock::get, { name })
        val alerts = CopyOnWriteArrayList<CheckIn>()
        val gps = FakeLocationSource()
        val sentLocal = CopyOnWriteArrayList<Event>()
        val live = LiveShareRepository(
            outbox, pool, { keys }, { ring }, gps, scope, { name }, clock::get,
            sendLocal = { event -> room.publish(event); sentLocal += event },
        )

        init {
            pins.startIngest(scope, inbox)
            checkIns.startIngest(inbox)
            checkIns.onAlert = { alerts += it }
            live.startIngest(inbox)
            outbox.start(scope)
            node.startSync()
        }

        suspend fun queuedFor(recipient: Phone): List<Event> {
            val rows = db.outboxDao().pending()
            assertTrue("The repository must have queued encrypted envelopes", rows.isNotEmpty())
            assertTrue(rows.all { it.state == Delivery.Saved.name && it.carrier == Carrier.SAVED.name && it.attempts == 0 })
            assertEquals(0, room.peerCount.value)
            assertFalse(node.envelopeRoute.ready.value)
            return rows.map { Event.fromJson(it.json) }.filter { recipient.keys.pubkeyHex in it.tagValues("p") }.also { events ->
                assertTrue("A wrap must be addressed to the other phone", events.isNotEmpty())
                assertTrue(events.all { it.kind == Kinds.GIFT_WRAP && it.validate() })
            }
        }

        suspend fun awaitCheckIn(status: CheckInStatus): CheckInView = withTimeout(15_000) {
            checkIns.recent().first { rows -> rows.any { it.checkIn.status == status } }.single { it.checkIn.status == status }
        }

        suspend fun assertCarried(events: List<Event>, recipient: Phone) {
            for (event in events) {
                withTimeout(15_000) { outbox.deliveryOf(event.id).first { it == Delivery.LeftPhone } }
                val sent = db.outboxDao().get(event.id)!!
                assertEquals(Carrier.NODE.name, sent.carrier)
                assertEquals(0, sent.attempts)
                val received = withTimeout(15_000) {
                    while (recipient.db.eventDao().get(event.id) == null) delay(10)
                    recipient.db.eventDao().get(event.id)!!
                }
                assertEquals(Carrier.NODE.name, received.carrier)
                assertEquals(event.toJson(), received.json)
            }
            assertIsolated()
        }
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    private fun assertIsolated() {
        phones.forEach { phone ->
            assertTrue(phone.pool.status.value.isEmpty())
            assertEquals(0, phone.pool.connectedCount)
            assertFalse(phone.room.node)
            assertEquals(0, phone.room.nodePeers.value)
            assertFalse(phone.node.route.ready.value)
            assertFalse(phone.node.node.value)
        }
    }

    private suspend fun connect(alice: Phone, bob: Phone) {
        alice.room.start()
        bob.room.start()
        for (phone in listOf(alice, bob)) {
            withTimeout(10_000) { phone.room.pool.status.first { peers -> peers.values.any { it == RelayStatus.CONNECTED } } }
            withTimeout(10_000) { phone.node.envelopeRoute.ready.first { it } }
            assertTrue(phone.room.pool.status.value.keys.all { it.startsWith("ws://127.0.0.1:") })
        }
        assertIsolated()
        // Do not flush explicitly: Outbox.start must drain Saved rows when the LAN route opens.
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        phones.forEach { it.room.stop(); it.pool.stop(); it.db.close() }
    }

    @Test
    fun `shared pin saved without peers decrypts when ordinary LAN connects`() = runBlocking<Unit> {
        val alice = phone("Tania")
        val bob = phone("Rafi")
        val pin = alice.pins.save("Flood shelter", "School second floor", lake, Category.ALERT, PinVisibility.SHARED, listOf(bob.keys.pubkeyHex))
        val queued = alice.queuedFor(bob)
        assertTrue(queued.none { it.content.contains("Flood shelter") || it.content.contains("School second floor") })
        assertTrue(bob.pins.pins().first().isEmpty())

        connect(alice, bob)

        val received = withTimeout(15_000) { bob.pins.pins().first { rows -> rows.any { it.pin.id == pin.id } } }.single()
        assertEquals(pin.id, received.pin.id)
        assertEquals(alice.keys.pubkeyHex, received.pin.owner)
        assertEquals(PinVisibility.SHARED, received.pin.visibility)
        assertEquals("Flood shelter", received.pin.title)
        assertEquals("School second floor", received.pin.note)
        assertEquals(lake.lat, received.pin.position.lat, .00001)
        assertEquals(lake.lng, received.pin.position.lng, .00001)
        assertFalse(received.mine)
        alice.assertCarried(queued, bob)
    }

    @Test
    fun `safe check-in waits Saved then reaches its watcher over LAN only`() = runBlocking<Unit> {
        val alice = phone("Nabila")
        val bob = phone("Ma")
        val sent = alice.checkIns.checkIn("Home", listOf(bob.keys.pubkeyHex), "wh0r0", "Reached safely")
        val queued = alice.queuedFor(bob)
        assertTrue(queued.none { it.content.contains("Reached safely") })
        assertTrue(bob.checkIns.recent().first().isEmpty())

        connect(alice, bob)

        val received = bob.awaitCheckIn(CheckInStatus.SAFE)
        assertEquals(sent, received.checkIn)
        assertFalse(received.own)
        assertTrue(alice.checkIns.recent().first().single().own)
        assertTrue(bob.alerts.isEmpty())
        alice.assertCarried(queued, bob)
    }

    @Test
    fun `overdue alert queued offline arrives once over ordinary LAN`() = runBlocking<Unit> {
        val alice = phone("Nabila")
        val bob = phone("Ma")
        val trip = alice.checkIns.startTrip("Banani 11", clock.get() / 1000 + 60, listOf(bob.keys.pubkeyHex), "Dhanmondi")
        clock.addAndGet(61_000)
        assertTrue(alice.checkIns.checkOverdue(trip.id))
        assertEquals(TripStatus.OVERDUE, alice.checkIns.tripById(trip.id)!!.status)
        val queued = alice.queuedFor(bob)
        assertEquals(2, queued.size)
        assertTrue(bob.checkIns.recent().first().isEmpty())

        connect(alice, bob)

        val received = bob.awaitCheckIn(CheckInStatus.OVERDUE)
        assertEquals(alice.keys.pubkeyHex, received.checkIn.author)
        assertEquals(trip.id, received.checkIn.tripId)
        assertEquals(trip.due, received.checkIn.due)
        assertEquals("Banani 11", received.checkIn.destination)
        assertEquals("Nabila", received.checkIn.name)
        assertFalse(received.own)
        withTimeout(5_000) { while (bob.alerts.isEmpty()) delay(10) }
        assertEquals(listOf(received.checkIn.id), bob.alerts.map { it.id })
        assertTrue(alice.alerts.isEmpty())
        alice.assertCarried(queued, bob)
        // Both phones' room servers may forward the same ciphertext; GiftInbox must dispatch it once.
        queued.forEach { assertNull(bob.inbox.offer(it)) }
        assertEquals(1, bob.checkIns.recent().first().count { it.checkIn.status == CheckInStatus.OVERDUE })
        assertEquals(1, bob.alerts.size)
    }

    @Test
    fun `live-share starts offline then delivers ephemeral GPS and stop over ordinary LAN`() = runBlocking<Unit> {
        val alice = phone("Rafi")
        val bob = phone("Tania")
        val share = alice.live.start(listOf(bob.keys.pubkeyHex), LiveDuration.HOUR_1)
        val queued = alice.queuedFor(bob)
        assertTrue(bob.live.people().first().isEmpty())

        connect(alice, bob)

        val received = withTimeout(15_000) { bob.live.people().first { it.isNotEmpty() } }.single()
        assertEquals(alice.keys.pubkeyHex, received.owner)
        assertEquals(share.shareId, received.shareId)
        assertEquals("Rafi", received.name)
        assertEquals(share.end, received.end)
        assertNull(received.position)
        alice.assertCarried(queued, bob)

        val queuedBeforePoint = alice.db.outboxDao().observeAll().first().map { it.eventId }.toSet()
        alice.gps.emit(lake)
        val pointView = withTimeout(15_000) {
            bob.live.people().first { it.singleOrNull()?.position == lake }
        }.single()
        assertEquals(share.shareId, pointView.shareId)
        assertEquals(8, pointView.accuracyMeters)
        withTimeout(10_000) { alice.live.outgoing.first { it?.sent == 1 } }
        val point = alice.sentLocal.single()
        assertEquals(Kinds.EPHEMERAL_GIFT_WRAP, point.kind)
        assertTrue(point.validate())
        assertEquals(listOf(bob.keys.pubkeyHex), point.tagValues("p"))
        assertFalse(point.content.contains("23.7465"))
        assertFalse(point.content.contains("90.376"))
        assertTrue(runCatching { GiftWrap.unwrap(point, alice.keys) }.isFailure)
        val opened = GiftWrap.unwrap(point, bob.keys)
        assertEquals(Kinds.LIVE_LOCATION, opened.kind)
        assertEquals("1", opened.tagValue("seq"))
        assertEquals(share.shareId, opened.tagValue("share"))
        withTimeout(10_000) { while (bob.receivedEphemeral.none { it.id == point.id }) delay(10) }
        assertEquals(queuedBeforePoint, alice.db.outboxDao().observeAll().first().map { it.eventId }.toSet())
        for (phone in listOf(alice, bob)) {
            assertNull(phone.db.outboxDao().get(point.id))
            assertNull(phone.db.eventDao().get(point.id))
            assertTrue(phone.room.relay.stored().none { it.id == point.id })
        }

        alice.live.stop()
        assertNull(alice.live.outgoing.value)
        withTimeout(15_000) { bob.live.people().first { it.isEmpty() } }
        withTimeout(5_000) { while (alice.gps.listeners != 0) delay(10) }
        val stop = alice.sentLocal.last()
        assertEquals(2, alice.sentLocal.size)
        assertEquals(Kinds.EPHEMERAL_GIFT_WRAP, stop.kind)
        val openedStop = GiftWrap.unwrap(stop, bob.keys)
        assertEquals("stopped", openedStop.tagValue("status"))
        assertEquals("2", openedStop.tagValue("seq"))
        withTimeout(10_000) { while (bob.receivedEphemeral.none { it.id == stop.id }) delay(10) }
        for (phone in listOf(alice, bob)) {
            assertNull(phone.db.outboxDao().get(stop.id))
            assertNull(phone.db.eventDao().get(stop.id))
            assertTrue(phone.room.relay.stored().none { it.id == stop.id })
        }
        assertIsolated()
    }
}
