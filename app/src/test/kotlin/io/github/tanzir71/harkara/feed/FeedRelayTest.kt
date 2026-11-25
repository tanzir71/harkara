package io.github.tanzir71.harkara.feed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.feed.ComposeVerdict
import io.github.tanzir71.harkara.core.feed.ReportReason
import io.github.tanzir71.harkara.core.feed.WordFilters
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.ui.components.Delivery
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Gate for M4: publish → receive → expiry → hide-after-3-reports over a MockWebServer NIP-01 relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRelayTest {
    private var clock = 1_764_000_000_000L
    private val relay = FakeNostrRelay { clock / 1000 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = mutableListOf<Phone>()
    private val dhanmondi = LatLng(23.7465, 90.376)
    private val nearby = LatLng(23.7480, 90.3790)
    private val filters = WordFilters.parse(File("../data/word-filters/filters.json").readText())

    inner class Phone(connect: Boolean = true) {
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope, now = { clock })
        val outbox = Outbox(db, pool, now = { clock })
        private val keys = mutableMapOf<String, KeyPair>()
        val feed = FeedRepository(db, RoomEventStore(db) { clock }, outbox, pool, filters, { g5 -> keys.getOrPut(g5) { KeyPair.generate() } }, nowMillis = { clock })

        init {
            outbox.start(scope)
            feed.startIngest(scope)
            if (connect) pool.setRelays(listOf(relay.url))
        }

        suspend fun awaitConnected() = withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
    }

    private fun phone(connect: Boolean = true) = Phone(connect).also { phones += it }

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

    @Test
    fun `post travels through the relay, expires, and hides after three reports`() = runBlocking {
        val alice = phone()
        val bob = phone()
        alice.awaitConnected()
        bob.awaitConnected()
        bob.feed.subscribeArea(dhanmondi)

        val result = alice.feed.post("Gridlocked from Science Lab to Kalabagan", Category.TRAFFIC, mapOf("traffic" to "3"), nearby)
        assertEquals(ComposeVerdict.OK, result.verdict)
        val postId = result.event!!.id

        // Alice's outbox reaches the network once the relay says OK.
        withTimeout(10_000) { alice.outbox.deliveryOf(postId).first { it == Delivery.ReachedNetwork } }

        // Bob receives it live and it shows in his Room-backed feed.
        val received = withTimeout(10_000) { bob.feed.feed(dhanmondi).first { items -> items.any { it.post.id == postId } } }
        val item = received.single { it.post.id == postId }
        assertEquals(Category.TRAFFIC, item.post.category)
        assertTrue(item.distanceMeters < 3000)

        // Two reports: still visible. Third distinct key: hidden on Bob's phone.
        bob.feed.subscribeReactions(listOf(postId))
        val reporters = (1..3).map { phone().also { it.awaitConnected() } }
        val post = item.post
        reporters.take(2).forEach { it.feed.report(post, ReportReason.SPAM) }
        withTimeout(10_000) { bob.feed.feed(dhanmondi).first { items -> items.single { it.post.id == postId }.moderation.reporters == 2 } }
        reporters[2].feed.report(post, ReportReason.SPAM)
        withTimeout(10_000) { bob.feed.feed(dhanmondi).first { items -> items.none { it.post.id == postId } } }

        // Expiry: a fresh traffic post disappears after 3 hours, on the phone and at the relay.
        clock += 61_000
        val second = alice.feed.post("Green Road is moving now", Category.TRAFFIC, emptyMap(), nearby).event!!
        withTimeout(10_000) { bob.feed.feed(dhanmondi).first { items -> items.any { it.post.id == second.id } } }
        clock += 3 * 3600 * 1000L + 1000
        assertFalse(bob.feed.feed(dhanmondi).first().any { it.post.id == second.id })
        val late = phone()
        late.awaitConnected()
        late.feed.subscribeArea(dhanmondi)
        Thread.sleep(500)
        assertFalse(late.feed.feed(dhanmondi).first().any { it.post.id == second.id })
    }

    @Test
    fun `negentropy catch-up fetches only the posts we are missing`() = runBlocking {
        relay.supportsNegentropy = true
        val posts = (0 until 6).map { i ->
            io.github.tanzir71.harkara.core.feed.PostComposer.compose(
                KeyPair.generate(), "Post number $i", Category.CHATTER, nearby.lat, nearby.lng, clock / 1000 - i * 60, powBits = 16,
            )
        }
        relay.events.addAll(posts)
        val bob = phone(connect = false)
        posts.take(2).forEach { assertTrue(bob.feed.ingest(it, io.github.tanzir71.harkara.core.Carrier.ONLINE)) }
        bob.pool.setRelays(listOf(relay.url))
        bob.awaitConnected()
        withTimeout(10_000) { bob.pool.info.first { it.values.any { info -> info.supportsNegentropy } } }

        assertEquals(1, bob.feed.catchUp(dhanmondi))
        val items = withTimeout(10_000) { bob.feed.feed(dhanmondi).first { it.size == 6 } }
        assertEquals(posts.map { it.id }.toSet(), items.map { it.post.id }.toSet())
        assertEquals(4, bob.pool.negentropyFetched.value)
        assertEquals(1, relay.negOpens)
    }

    @Test
    fun `posts without proof of work or over the rate limit are dropped`() = runBlocking {
        val bob = phone()
        val keys = KeyPair.generate()
        val weak = io.github.tanzir71.harkara.core.feed.PostComposer.compose(keys, "no work", Category.CHATTER, nearby.lat, nearby.lng, clock / 1000, powBits = 0)
            .takeIf { io.github.tanzir71.harkara.core.nostr.Nip13.difficulty(it.id) < 16 }
        if (weak != null) assertFalse(bob.feed.ingest(weak, io.github.tanzir71.harkara.core.Carrier.ONLINE))

        val first = io.github.tanzir71.harkara.core.feed.PostComposer.compose(keys, "one", Category.CHATTER, nearby.lat, nearby.lng, clock / 1000, powBits = 16)
        val second = io.github.tanzir71.harkara.core.feed.PostComposer.compose(keys, "two", Category.CHATTER, nearby.lat, nearby.lng, clock / 1000 + 10, powBits = 16)
        assertTrue(bob.feed.ingest(first, io.github.tanzir71.harkara.core.Carrier.ONLINE))
        assertFalse(bob.feed.ingest(second, io.github.tanzir71.harkara.core.Carrier.ONLINE))
    }

    @Test
    fun `own posting is rate limited and filtered`() = runBlocking {
        val alice = phone(connect = false)
        assertEquals(ComposeVerdict.OK, alice.feed.post("Khichuri at the canteen", Category.FOOD, emptyMap(), nearby).verdict)
        assertEquals(ComposeVerdict.RATE_LIMITED, alice.feed.post("Again", Category.FOOD, emptyMap(), nearby).verdict)
        clock += 61_000
        assertEquals(ComposeVerdict.SPAM, alice.feed.post("click here to earn money", null, emptyMap(), nearby).verdict)
        // Saved offline: with no relay connected the post waits in the outbox.
        assertEquals(1, alice.outbox.waiting.first())
    }
}
