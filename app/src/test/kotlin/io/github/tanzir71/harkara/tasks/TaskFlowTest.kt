package io.github.tanzir71.harkara.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.tasks.HandoffProof
import io.github.tanzir71.harkara.core.tasks.TaskState
import io.github.tanzir71.harkara.core.tasks.TaskStatus
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gate for M7: the whole task over a mock relay, with the handoff checked offline (no carrier at all). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskFlowTest {
    private var clock = 1_764_000_000_000L
    private val relay = FakeNostrRelay { clock / 1000 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val area = LatLng(23.7465, 90.376)
    private val phones = mutableListOf<Phone>()

    inner class Phone(name: String) {
        val keys = KeyPair.generate()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope, now = { clock })
        val outbox = Outbox(db, pool) { clock }
        val tasks = TaskRepository(db, RoomEventStore(db) { clock }, outbox, pool, { keys }, { name }) { clock }

        init {
            outbox.start(scope)
            tasks.startIngest(scope)
        }

        fun online() = pool.setRelays(listOf(relay.url))
        fun offline() = pool.stop()

        suspend fun awaitConnected() = withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }

        suspend fun await(condition: (TaskState) -> Boolean): TaskState =
            withTimeout(15_000) { tasks.tasks(area).first { list -> list.any(condition) } }.first(condition)
    }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        phones.forEach { it.pool.stop(); it.db.close() }
        scope.cancel()
        relay.shutdown()
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    @Test
    fun `post, accept, countersign, offline handoff, payment and ratings`() = runBlocking {
        val tania = phone("Tania")
        val sumon = phone("Sumon Ahmed")
        tania.online(); sumon.online()
        tania.awaitConnected(); sumon.awaitConnected()
        tania.tasks.subscribeArea(area)
        sumon.tasks.subscribeArea(area)

        val offerEvent = tania.tasks.post("pickup", "Two insulin pens from the Road 8 pharmacy to House 12", 120,
            mapOf("from" to "Road 8 pharmacy", "to" to "House 12"), "Ask for the cold pack.", area)
        val offerId = offerEvent.id

        // Sumon sees the open task nearby and offers to run it.
        val seen = sumon.await { it.offer.event.id == offerId && it.status == TaskStatus.OPEN }
        assertEquals(TaskAction.ACCEPT, sumon.tasks.nextAction(seen))
        sumon.tasks.accept(seen.offer)

        // Accepting alone isn't enough: Tania countersigns.
        tania.tasks.subscribeSteps(listOf(offerId))
        val offered = tania.await { it.offer.event.id == offerId && it.pendingAccepts.isNotEmpty() }
        assertEquals(TaskStatus.OPEN, offered.status)
        assertEquals("Sumon Ahmed", offered.pendingAccepts.single().tagValue("name"))
        assertEquals(TaskAction.CONFIRM_RUNNER, tania.tasks.nextAction(offered))
        tania.tasks.confirm(offered.offer, offered.pendingAccepts.single())

        sumon.tasks.subscribeSteps(listOf(offerId))
        val accepted = sumon.await { it.offer.event.id == offerId && it.status == TaskStatus.ACCEPTED }
        assertEquals(sumon.keys.pubkeyHex, accepted.runner)
        sumon.tasks.pickedUp(accepted.offer)
        sumon.await { it.offer.event.id == offerId && it.status == TaskStatus.PICKED_UP }

        // Now every carrier is gone. Tania shows the code; Sumon's phone checks it alone.
        tania.offline(); sumon.offline()
        val display = tania.tasks.handoffFor(offered.offer)
        assertNotNull(display)
        val local = sumon.tasks.tasks(area).first().single { it.offer.event.id == offerId }
        val wrong = ((display!!.code.toInt() + 7) % 1_000_000).toString().padStart(6, '0')
        assertFalse(sumon.tasks.deliver(local, wrong))
        assertTrue(sumon.tasks.deliver(local, display.code.substring(0, 3) + " " + display.code.substring(3)))
        val delivered = sumon.tasks.tasks(area).first().single { it.offer.event.id == offerId }
        assertEquals(TaskStatus.DELIVERED, delivered.status)
        assertEquals(HandoffProof.CODE, delivered.proof)
        assertEquals(0, sumon.pool.connectedCount)

        // Back online: the delivered step reaches Tania; payment is recorded and countersigned.
        clock += 60_000
        tania.online(); sumon.online()
        tania.awaitConnected(); sumon.awaitConnected()
        tania.tasks.subscribeSteps(listOf(offerId)); sumon.tasks.subscribeSteps(listOf(offerId))
        val atTania = tania.await { it.offer.event.id == offerId && it.status == TaskStatus.DELIVERED }
        assertEquals(TaskAction.RECORD_PAYMENT, tania.tasks.nextAction(atTania))
        tania.tasks.recordPayment(atTania, PayMethod.BKASH, "9F4K2L7Q")
        val toConfirm = sumon.await { it.offer.event.id == offerId && it.requesterPaid }
        assertEquals(PaymentDetails("bkash", 120, "9F4K2L7Q"), sumon.tasks.paymentDetails(toConfirm))
        sumon.tasks.confirmPayment(toConfirm)
        val paid = tania.await { it.offer.event.id == offerId && it.status == TaskStatus.PAID }
        tania.tasks.rate(paid, 5)
        sumon.tasks.rate(sumon.await { it.offer.event.id == offerId && it.status == TaskStatus.PAID }, 5)
        val rated = tania.await { it.offer.event.id == offerId && it.ratings.size == 2 }
        assertTrue(rated.dropped.isEmpty())
    }

    @Test
    fun `a scanned QR token also delivers offline, and only the runner can use it`() = runBlocking {
        val tania = phone("Tania")
        val sumon = phone("Sumon")
        val stranger = phone("Rafi")
        tania.online(); sumon.online(); stranger.online()
        listOf(tania, sumon, stranger).forEach { it.awaitConnected(); it.tasks.subscribeArea(area) }
        val offerId = tania.tasks.post("errand", "Queue for the gas cylinder", 80, mapOf("where" to "Road 2", "instructions" to "Hold a place"), "", area).id
        sumon.tasks.accept(sumon.await { it.offer.event.id == offerId }.offer)
        tania.tasks.subscribeSteps(listOf(offerId))
        val offered = tania.await { it.offer.event.id == offerId && it.pendingAccepts.isNotEmpty() }
        tania.tasks.confirm(offered.offer, offered.pendingAccepts.single())
        sumon.tasks.subscribeSteps(listOf(offerId)); stranger.tasks.subscribeSteps(listOf(offerId))
        val atSumon = sumon.await { it.offer.event.id == offerId && it.status == TaskStatus.ACCEPTED }
        val atStranger = stranger.await { it.offer.event.id == offerId && it.status == TaskStatus.ACCEPTED }

        tania.offline(); sumon.offline(); stranger.offline()
        val qr = tania.tasks.handoffFor(offered.offer)!!.qrPayload
        assertFalse(stranger.tasks.deliver(atStranger, qr))
        assertTrue(sumon.tasks.deliver(atSumon, qr))
        assertEquals(HandoffProof.TOKEN, sumon.tasks.tasks(area).first().single { it.offer.event.id == offerId }.proof)
    }
}
