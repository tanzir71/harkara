package io.github.tanzir71.harkara.links

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.khata.IouConfirm
import io.github.tanzir71.harkara.core.khata.IouStatus
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.sms.SmsEligibility
import io.github.tanzir71.harkara.core.tasks.Handoff
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.khata.IouDirection
import io.github.tanzir71.harkara.khata.KhataRepository
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.tasks.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What happens when an SMS, a QR code or a link reaches the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkInboxTest {
    private val relay = FakeNostrRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = mutableListOf<Phone>()
    private val area = LatLng(23.7465, 90.376)

    inner class Phone(val name: String) {
        val keys = KeyPair.generate()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool)
        val store = RoomEventStore(db)
        val tasks = TaskRepository(db, store, outbox, pool, { keys }, { name })
        val khata = KhataRepository(db, outbox, { listOf(pool) }, { keys }, { name })
        val inbox = LinkInbox(db, store, outbox, tasks, khata, { keys.pubkeyHex })

        init {
            outbox.start(scope)
            khata.startIngest(scope, pool)
        }

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
        }

        suspend fun await(id: String, status: IouStatus) =
            withTimeout(15_000) { khata.ious().first { list -> list.any { it.terms.id == id && it.status == status } } }.first { it.terms.id == id }
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        phones.forEach { it.pool.stop() }
        // Stop every background writer before the databases close, so nothing leaks into later tests.
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        phones.forEach { it.db.close() }
        relay.shutdown()
    }

    @Test
    fun `a task step that came by SMS is verified with known keys, stored once and lifted to the relays`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val rafi = phone("Rafi")
        val offerEvent = tania.tasks.post("buy", "Napa Extra ene din", 120, mapOf("store" to "Lazz Pharma"), "", area)
        val offer = TaskOffers.parse(offerEvent)!!
        assertTrue(rafi.inbox.ingest(offerEvent, Carrier.QR))
        val accept = rafi.tasks.accept(offer)
        tania.tasks.ingest(accept, Carrier.ONLINE)

        val picked = TaskSteps.pickedUp(rafi.keys, offer, offerEvent.createdAt + 600)
        val sms = SmsEligibility.smsText(picked)!!
        val received = tania.inbox.handle(HarkaraLink.Sms("Fwd: $sms")) as LinkInbox.Outcome.Received
        assertEquals(picked, received.event)
        assertEquals(Carrier.SMS, received.via)
        assertTrue(received.isNew)
        assertNotNull(tania.db.eventDao().get(picked.id))
        assertNotNull("an off-grid step is lifted onto the relays unchanged", tania.db.outboxDao().get(picked.id))
        assertEquals(false, (tania.inbox.handle(HarkaraLink.Sms(sms)) as LinkInbox.Outcome.Received).isNew)

        // Someone who has never seen Rafi's key cannot verify it, so nothing is stored.
        val stranger = phone("Stranger")
        assertEquals(LinkInbox.Outcome.Unverified, stranger.inbox.handle(HarkaraLink.Sms(sms)))
    }

    @Test
    fun `a long event arrives as several QR codes`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val rafi = phone("Rafi")
        val offerEvent = tania.tasks.post("errand", "Pick up the parcel", 80, emptyMap(), (1..12).joinToString(" ") { java.util.UUID.randomUUID().toString() }, area)
        val codes = QrTransport.encode(offerEvent, maxChars = 300)
        assertTrue(codes.size > 2)
        codes.dropLast(1).forEachIndexed { i, code ->
            assertEquals(LinkInbox.Outcome.Progress(i + 1, codes.size), rafi.inbox.handle(HarkaraLink.QrPart(code)))
        }
        val done = rafi.inbox.handle(HarkaraLink.QrPart(codes.last())) as LinkInbox.Outcome.Received
        assertEquals(offerEvent, done.event)
        assertNotNull(rafi.db.eventDao().get(offerEvent.id))
    }

    @Test
    fun `someone without the app confirms on the web page and the IOU becomes agreed`() = runBlocking<Unit> {
        val tania = phone("Tania")
        tania.online()
        val claim = tania.khata.create(IouDirection.THEY_OWE_ME, Party("Karim"), 1250, "Cha-er dokan")
        assertEquals(IouStatus.CLAIMED, claim.status)
        val reminder = tania.khata.reminderLink(claim)
        assertTrue(reminder, reminder.startsWith("https://tanzir71.github.io/harkara/o/#c:"))

        // The page decodes the link, checks Tania's signature and confirms with a throwaway key...
        val link = (HarkaraLinks.parse(reminder) as HarkaraLink.IouConfirm).link
        assertTrue(link.codeMatches)
        val confirmation = IouConfirm.confirm(KeyPair.generate(), link.terms, link.code, claim.terms.createdAt + 60)
        // ...and publishes it to public relays.
        val browser = RelayPool(scope)
        browser.setRelays(listOf(relay.url))
        withTimeout(10_000) { browser.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
        browser.publish(confirmation)
        tania.khata.subscribeConfirmations()
        tania.await(claim.terms.id, IouStatus.AGREED)
        browser.stop()

        // The same confirmation can also come back as an o/ link the person texts to Tania.
        val second = tania.khata.create(IouDirection.THEY_OWE_ME, Party("Mitu"), 300, "Bus fare")
        val secondLink = (HarkaraLinks.parse(tania.khata.reminderLink(second)) as HarkaraLink.IouConfirm).link
        val back = HarkaraLinks.web(HarkaraLinks.eventRest(IouConfirm.confirm(KeyPair.generate(), secondLink.terms, secondLink.code, second.terms.createdAt + 5)))
        tania.inbox.handle(HarkaraLinks.parse(back)!!)
        assertEquals(IouStatus.AGREED, tania.khata.get(second.terms.id)!!.status)
        assertEquals(LinkInbox.Outcome.OpenIou(second.terms.id), tania.inbox.handle(HarkaraLinks.parse(tania.khata.reminderLink(second))!!))
        assertEquals(LinkInbox.Outcome.OpenIou(null), phone("Other").inbox.handle(HarkaraLinks.parse(tania.khata.reminderLink(second))!!))

        // IOUs with someone who has a key keep the help link.
        val keyed = tania.khata.create(IouDirection.THEY_OWE_ME, Party("Rafi", KeyPair.generate().pubkeyHex), 50, "Tea")
        assertEquals(KhataRepository.HELP_URL, tania.khata.reminderLink(keyed))
    }

    @Test
    fun `handoff, task, area and gateway links tell the UI where to go`() = runBlocking<Unit> {
        val tania = phone("Tania")
        val offerEvent = tania.tasks.post("buy", "Bread", 60, emptyMap(), "", area)
        val offer = TaskOffers.parse(offerEvent)!!
        val token = Handoff.token(tania.keys, offer, KeyPair.generate().pubkeyHex, offerEvent.createdAt)
        val handoff = "harkara:handoff:" + token.toJson()
        assertEquals(LinkInbox.Outcome.OpenHandoff(handoff, offer.address), tania.inbox.handle(HarkaraLinks.parse(handoff)!!))
        assertEquals(LinkInbox.Outcome.FollowTask(tania.keys.pubkeyHex, offer.d), tania.inbox.handle(HarkaraLinks.parse(HarkaraLinks.web(HarkaraLinks.taskRest(tania.keys.pubkeyHex, offer.d)))!!))
        assertEquals(LinkInbox.Outcome.OpenArea("wh0r0"), tania.inbox.handle(HarkaraLinks.parse("harkara:g:wh0r0")!!))
        val card = GatewayCard("Nabila", "01822000222")
        assertEquals(LinkInbox.Outcome.SavedGateway(card), tania.inbox.handle(HarkaraLinks.parse(card.toLink())!!))
        assertEquals(card, tania.inbox.savedGateway())
    }
}
