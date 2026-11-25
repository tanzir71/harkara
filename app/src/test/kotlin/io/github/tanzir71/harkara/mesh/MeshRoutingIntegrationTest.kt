package io.github.tanzir71.harkara.mesh

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.mesh.FakeRadioAir
import io.github.tanzir71.harkara.core.mesh.MeshEngine
import io.github.tanzir71.harkara.core.mesh.MeshProfile
import io.github.tanzir71.harkara.core.mesh.MeshRadio
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.store.EventStore
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.OutboxRoute
import io.github.tanzir71.harkara.relay.Priority
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.ui.components.Delivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshRoutingIntegrationTest {
    private val clock = 1_764_000_000_000L
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val store = RoomEventStore(db) { clock }
    private val pool = RelayPool(scope, now = { clock })
    private val relay = FakeNostrRelay { clock / 1000 }
    private val air = FakeRadioAir(mtu = 120)
    private val controllers = mutableListOf<MeshController>()
    private val engines = mutableListOf<MeshEngine>()
    private var relayStarted = false

    @After
    fun tearDown() = runBlocking {
        controllers.forEach { it.stop() }
        engines.forEach { it.stop() }
        air.awaitIdle()
        air.shutdown()
        pool.stop()
        job.cancelAndJoin()
        db.close()
        if (relayStarted) relay.shutdown()
    }

    private fun event() = KeyPair.generate().signEvent(
        Kinds.SOS,
        listOf(listOf("g", "wh0r0d"), listOf("expiration", (clock / 1000 + 3_600).toString())),
        "Water rising at Road 7. ".repeat(12),
        clock / 1000,
    )

    private fun controller(name: String, radio: MeshRadio = air.radio(name)) = MeshController(
        scope, db, store, pool,
        presenceId = { ByteArray(16) { (name.hashCode() + it).toByte() } },
        radios = { listOf(radio) }, areaPrefix = { "wh0r" }, clock = { clock }, tickMillis = 60_000,
    ).also { controllers += it }

    private fun engine(name: String, received: MutableList<Event> = CopyOnWriteArrayList()) = MeshEngine(
        EventStore(setOf("wh0r")), { ByteArray(16) { (name.hashCode() + it).toByte() } },
        { Instant.ofEpochMilli(clock) }, MeshProfile.EVENT,
        object : MeshEngine.Listener {
            override fun onEvent(event: Event, hops: Int, peer: String) { received += event }
        },
    ).also { engines += it; it.attach(air.radio(name)) }

    @Test
    fun `saved Outbox event leaves once when a fake mesh rung opens`() = runBlocking {
        val handedOver = CopyOnWriteArrayList<Event>()
        val route = object : OutboxRoute {
            override val carrier = Carrier.MESH
            override val ready = MutableStateFlow(false)
            override suspend fun carry(event: Event): Boolean = synchronized(handedOver) {
                if (handedOver.any { it.id == event.id }) false else { handedOver += event; true }
            }
        }
        val outbox = Outbox(db, pool, routes = listOf(route), now = { clock })
        outbox.start(scope)
        val event = event()
        assertTrue(store.store(event, Carrier.SAVED, own = true))
        outbox.enqueue(event, Priority.SOS)
        assertEquals(Delivery.Saved, outbox.deliveryOf(event.id).first())
        assertTrue(handedOver.isEmpty())

        route.ready.value = true
        withTimeout(10_000) { outbox.deliveryOf(event.id).first { it == Delivery.LeftPhone } }
        repeat(3) { outbox.flush() }
        route.ready.value = false
        route.ready.value = true
        outbox.flush()

        assertEquals(listOf(event.toJson()), handedOver.map { it.toJson() })
        assertEquals(Carrier.MESH.name, db.outboxDao().get(event.id)!!.carrier)
        assertEquals(0, pool.connectedCount)
        assertTrue(relay.events.isEmpty())
    }

    @Test
    fun `persisted own event is not premarked by mesh seed and leaves when a real fake radio connects`() = runBlocking {
        val sender = controller("sender")
        val outbox = Outbox(db, pool, now = { clock }, routes = listOf(sender.route))
        outbox.start(scope)
        val event = event()
        assertTrue(store.store(event, Carrier.SAVED, own = true))
        outbox.enqueue(event, Priority.SOS)
        assertEquals(Delivery.Saved, outbox.deliveryOf(event.id).first())
        sender.start(MeshProfile.EVENT)
        val received = CopyOnWriteArrayList<Event>()
        engine("receiver", received)
        air.link("sender", "receiver")
        air.awaitIdle()
        withTimeout(10_000) { outbox.deliveryOf(event.id).first { it == Delivery.LeftPhone } }
        withTimeout(10_000) { while (received.none { it.id == event.id }) { air.awaitIdle(); delay(10) } }
        assertEquals(event.toJson(), received.single().toJson())
        assertEquals(Carrier.MESH.name, db.outboxDao().get(event.id)!!.carrier)
    }

    @Test
    fun `late carrier callbacks cannot downgrade relay acknowledgements or receipts`() = runBlocking {
        val outbox = Outbox(db, pool, now = { clock })
        val event = event()
        outbox.enqueue(event, Priority.SOS)
        db.outboxDao().setState(event.id, Delivery.Delivered.name, Carrier.MESH.name)
        db.outboxDao().recordAttempt(event.id, clock + 60_000, Carrier.ONLINE.name)
        db.outboxDao().setState(event.id, Delivery.ReachedNetwork.name, Carrier.ONLINE.name)
        assertEquals(Delivery.Delivered, outbox.deliveryOf(event.id).first())
        assertEquals(Carrier.MESH.name, db.outboxDao().get(event.id)!!.carrier)
    }

    @Test
    fun `three hop fake mesh event is stored and bridged unchanged to the local relay`() = runBlocking {
        relay.start()
        relayStarted = true
        pool.setRelays(listOf(relay.url))
        withTimeout(10_000) { pool.status.first { it.values.any { status -> status == RelayStatus.CONNECTED } } }
        val bridge = controller("bridge")
        bridge.start(MeshProfile.EVENT)
        val origin = engine("origin")
        engine("middle-a")
        engine("middle-b")
        air.link("origin", "middle-a")
        air.link("middle-a", "middle-b")
        air.link("middle-b", "bridge")
        air.awaitIdle()
        val event = event()
        val acknowledged = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) { pool.acks.first { it.eventId == event.id && it.accepted } }
        }

        assertTrue(origin.originate(event))
        air.awaitIdle()
        val row = withTimeout(10_000) {
            db.eventDao().observeKinds(listOf(Kinds.SOS)).first { rows -> rows.any { it.id == event.id } }
        }.single { it.id == event.id }
        acknowledged.await()
        withTimeout(10_000) { while (relay.events.none { it.id == event.id }) delay(10) }

        assertEquals(Carrier.MESH.name, row.carrier)
        assertFalse(row.own)
        assertEquals(event.toJson(), row.json)
        assertEquals(event.toJson(), relay.events.single { it.id == event.id }.toJson())
        assertTrue(relay.events.single().verify())
        assertFalse(origin.originate(event))
        bridge.tickNow()
        air.awaitIdle()
        assertEquals(1, relay.events.size)
    }

    @Test
    fun `controller is inert until opted in and stop shuts its radio down once`() = runBlocking {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val delegate = air.radio("phone")
        val radio = object : MeshRadio by delegate {
            override fun start(token: ByteArray, listener: MeshRadio.Listener) {
                starts.incrementAndGet()
                delegate.start(token, listener)
            }
            override fun stop() { stops.incrementAndGet(); delegate.stop() }
        }
        val controller = controller("phone", radio)
        assertFalse(controller.running)
        assertFalse(controller.route.ready.value)
        assertEquals(0, starts.get())

        controller.start(MeshProfile.SAVER)
        controller.start(MeshProfile.EVENT)
        assertEquals(1, starts.get())
        assertEquals(MeshProfile.EVENT, controller.status.value.profile)
        controller.stop()
        controller.stop()
        air.awaitIdle()

        assertEquals(1, stops.get())
        assertFalse(controller.running)
        assertFalse(controller.status.value.running)
        assertTrue(controller.status.value.peers.isEmpty())
        assertFalse(controller.route.carry(event()))
    }
}
