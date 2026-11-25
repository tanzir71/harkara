package io.github.tanzir71.harkara.places

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.location.LiveDuration
import io.github.tanzir71.harkara.core.location.LiveShares
import io.github.tanzir71.harkara.core.location.LiveVerdict
import io.github.tanzir71.harkara.core.nostr.GiftWrap
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** GPS stand-in: fixes arrive only when the test sends them, and it counts who is listening. */
class FakeLocationSource : LocationSource {
    private val fixes = Channel<Fix>(Channel.UNLIMITED)
    @Volatile var listeners = 0
    var last: Fix? = null

    fun emit(position: LatLng, accuracy: Int = 8) {
        val f = Fix(position, accuracy, System.currentTimeMillis())
        last = f
        fixes.trySend(f)
    }

    override fun updates(intervalMillis: Long): Flow<Fix> = kotlinx.coroutines.flow.flow {
        listeners++
        try {
            fixes.receiveAsFlow().collect { emit(it) }
        } finally {
            listeners--
        }
    }

    override fun lastKnown(): Fix? = last
}

/** Gate for M9: live share end to end, with replay protection, the end time and Stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveShareFlowTest {
    private val relay = FakeNostrRelay()
    // Room work still finishing after teardown must not leak into the next test as an uncaught exception.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val clock = AtomicLong(System.currentTimeMillis())
    private val phones = mutableListOf<Phone>()

    inner class Phone(name: String) {
        val ring = KeyRing(KeyRing.generateSeed(SecureRandom()))
        val keys = ring.profile()
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool)
        val inbox = GiftInbox({ keys }, { clock.get() })
        val gps = FakeLocationSource()
        val live = LiveShareRepository(outbox, pool, { keys }, { ring }, gps, scope, { name }, { clock.get() })

        init {
            outbox.start(scope)
            live.startIngest(inbox)
            inbox.start(scope, pool)
        }

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
            inbox.subscribe(pool)
        }
    }

    private fun phone(name: String) = Phone(name).also { phones += it }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        // Stop every coroutine before closing the databases they write to.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        phones.forEach { it.pool.stop(); it.db.close() }
        relay.shutdown()
    }

    @Test
    fun `points reach the named person, replays are dropped, stop ends it`() = runBlocking<Unit> {
        val rafi = phone("Rafi")
        val tania = phone("Tania")
        val stranger = phone("Someone")
        rafi.online(); tania.online(); stranger.online()

        assertEquals(0, rafi.gps.listeners)
        val share = rafi.live.start(listOf(tania.keys.pubkeyHex), LiveDuration.HOUR_1, minIntervalMillis = 0)
        withTimeout(5_000) { while (rafi.gps.listeners == 0) kotlinx.coroutines.delay(20) }
        assertEquals(share.end, clock.get() / 1000 + 3600)

        // The start travels as a stored gift wrap; once Tania holds it, points from the session key count.
        withTimeout(15_000) { tania.live.people().first { it.isNotEmpty() } }
        rafi.gps.emit(LatLng(23.7465, 90.3760))
        val seen = withTimeout(15_000) { tania.live.people().first { it.singleOrNull()?.position != null } }.single()
        assertEquals(rafi.keys.pubkeyHex, seen.owner)
        assertEquals("Rafi", seen.name)
        assertEquals(LatLng(23.7465, 90.376), seen.position)

        rafi.gps.emit(LatLng(23.7480, 90.3770))
        withTimeout(15_000) { tania.live.people().first { it.singleOrNull()?.position == LatLng(23.748, 90.377) } }

        // Points are ephemeral wraps only Tania can open: replaying one is refused.
        val pointWrap = relay.events.last { it.kind == Kinds.EPHEMERAL_GIFT_WRAP }
        assertTrue(relay.events.none { it.content.contains("23.74") })
        val rumor = GiftWrap.unwrap(pointWrap, tania.keys)
        assertEquals(LiveVerdict.REPLAY, tania.live.receive(rumor))
        assertTrue(stranger.live.people().first().isEmpty())
        assertTrue(runCatching { GiftWrap.unwrap(pointWrap, stranger.keys) }.isFailure)

        rafi.live.stop()
        assertNull(rafi.live.outgoing.value)
        withTimeout(5_000) { while (rafi.gps.listeners > 0) kotlinx.coroutines.delay(20) }
        withTimeout(15_000) { tania.live.people().first { it.isEmpty() } }
        // After the stop even a fresh point with a higher seq is refused.
        val late = LiveShares.pointRumor(rafi.ring.session(share.shareId), share.shareId, 99, share.end, LatLng(1.0, 1.0), null, clock.get() / 1000)
        assertEquals(LiveVerdict.ENDED, tania.live.receive(late))
    }

    @Test
    fun `a share stops by itself at its end time`() = runBlocking<Unit> {
        val rafi = phone("Rafi")
        val tania = phone("Tania")
        rafi.online(); tania.online()
        rafi.live.start(listOf(tania.keys.pubkeyHex), LiveDuration.MIN_15, minIntervalMillis = 0)
        withTimeout(5_000) { while (rafi.gps.listeners == 0) kotlinx.coroutines.delay(20) }
        rafi.gps.emit(LatLng(23.75, 90.38))
        withTimeout(15_000) { tania.live.people().first { it.singleOrNull()?.position != null } }

        clock.addAndGet(16 * 60_000L)
        rafi.gps.emit(LatLng(23.76, 90.39))
        withTimeout(10_000) { rafi.live.outgoing.first { it == null } }
        withTimeout(5_000) { while (rafi.gps.listeners > 0) kotlinx.coroutines.delay(20) }
        assertTrue(tania.live.people().first().isEmpty())
    }
}
