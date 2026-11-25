package io.github.tanzir71.harkara.places

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.tanzir71.harkara.core.location.CheckInStatus
import io.github.tanzir71.harkara.core.location.TripStatus
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicLong

/** Gate for M9: check-ins over the relay, and the overdue alert only when no check-in came in time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CheckInFlowTest {
    private val relay = FakeNostrRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = AtomicLong(System.currentTimeMillis())
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val phones = mutableListOf<Phone>()
    private lateinit var traveller: Phone

    inner class Phone(val keys: KeyPair = KeyPair.generate()) {
        val db = HarkaraDatabase.inMemory(context)
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool)
        val inbox = GiftInbox({ keys }, { clock.get() })
        val checkIns = CheckInRepository(db, outbox, { keys }, WorkManagerOverdueScheduler(context) { clock.get() }, { clock.get() })

        init {
            outbox.start(scope)
            checkIns.startIngest(inbox)
            inbox.start(scope, pool)
        }

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
            inbox.subscribe(pool)
        }

        suspend fun await(status: CheckInStatus) =
            withTimeout(15_000) { checkIns.recent(days = 30000).first { list -> list.any { it.checkIn.status == status } } }.first { it.checkIn.status == status }
    }

    private fun phone() = Phone().also { phones += it }

    @Before
    fun setUp() {
        relay.start()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == OverdueWorker::class.java.name) {
                    OverdueWorker(appContext, workerParameters, { traveller.checkIns }, { traveller.outbox.flush() })
                } else {
                    null
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        phones.forEach { it.pool.stop(); it.db.close() }
        scope.cancel()
        relay.shutdown()
    }

    private fun allWork(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(OverdueWorker.NAME).get()

    /** The job still waiting to run, if any. */
    private fun overdueWork(): WorkInfo? = allWork().firstOrNull { !it.state.isFinished }

    private suspend fun runOverdueWork(): WorkInfo {
        val work = overdueWork()!!
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(work.id)
        return withTimeout(10_000) {
            var info = WorkManager.getInstance(context).getWorkInfoById(work.id).get()!!
            while (!info.state.isFinished) {
                delay(20)
                info = WorkManager.getInstance(context).getWorkInfoById(work.id).get()!!
            }
            info
        }
    }

    @Test
    fun `check-in is signed, wrapped and arrives only for the chosen person`() = runBlocking<Unit> {
        traveller = phone()
        val mum = phone()
        val stranger = phone()
        traveller.online(); mum.online(); stranger.online()

        traveller.checkIns.checkIn("Home", listOf(mum.keys.pubkeyHex), geohash5 = "wh0r0", note = "Reached, all fine")
        val got = mum.await(CheckInStatus.SAFE)
        assertEquals(traveller.keys.pubkeyHex, got.checkIn.author)
        assertEquals("Home", got.checkIn.place)
        assertEquals("wh0r0", got.checkIn.geohash)
        assertFalse(got.own)
        assertTrue(traveller.checkIns.recent().first().single().own)
        assertTrue(relay.events.all { it.kind == Kinds.GIFT_WRAP && !it.content.contains("Home") })
        delay(500)
        assertTrue(stranger.checkIns.recent().first().isEmpty())
    }

    @Test
    fun `no check-in by the expected arrival sends the overdue alert`() = runBlocking<Unit> {
        traveller = phone()
        val mum = phone()
        traveller.online(); mum.online()

        val trip = traveller.checkIns.startTrip("Banani 11", clock.get() / 1000 + 3600, listOf(mum.keys.pubkeyHex), "Dhanmondi")
        assertEquals(WorkInfo.State.ENQUEUED, overdueWork()!!.state)
        mum.await(CheckInStatus.TRIP)

        clock.addAndGet(3_600_000L)
        assertEquals(WorkInfo.State.SUCCEEDED, runOverdueWork().state)
        assertEquals(TripStatus.OVERDUE, traveller.checkIns.tripById(trip.id)!!.status)
        val alert = mum.await(CheckInStatus.OVERDUE)
        assertEquals("Banani 11", alert.checkIn.destination)
        assertEquals(trip.due, alert.checkIn.due)
        assertEquals(traveller.keys.pubkeyHex, alert.checkIn.author)
    }

    @Test
    fun `a check-in before the deadline means no alert`() = runBlocking<Unit> {
        traveller = phone()
        val mum = phone()
        traveller.online(); mum.online()

        val trip = traveller.checkIns.startTrip("Banani 11", clock.get() / 1000 + 3600, listOf(mum.keys.pubkeyHex), "Dhanmondi")
        clock.addAndGet(40 * 60_000L)
        traveller.checkIns.checkIn("Banani 11", emptyList())
        assertEquals(listOf(WorkInfo.State.CANCELLED), allWork().map { it.state })
        assertEquals(TripStatus.ARRIVED, traveller.checkIns.tripById(trip.id)!!.status)

        clock.addAndGet(60 * 60_000L)
        assertFalse(traveller.checkIns.checkOverdue(trip.id))
        mum.await(CheckInStatus.SAFE)
        delay(500)
        assertTrue(mum.checkIns.recent(days = 30000).first().none { it.checkIn.status == CheckInStatus.OVERDUE })
    }

    @Test
    fun `an early run waits for the deadline instead of alerting`() = runBlocking<Unit> {
        traveller = phone()
        val mum = phone()
        traveller.online(); mum.online()

        val trip = traveller.checkIns.startTrip("Uttara", clock.get() / 1000 + 7200, listOf(mum.keys.pubkeyHex), "Gulshan")
        clock.addAndGet(30 * 60_000L)
        assertEquals(WorkInfo.State.SUCCEEDED, runOverdueWork().state)
        assertEquals(TripStatus.ACTIVE, traveller.checkIns.tripById(trip.id)!!.status)
        assertEquals(WorkInfo.State.ENQUEUED, withTimeout(5_000) {
            while (overdueWork()?.state != WorkInfo.State.ENQUEUED) delay(20)
            overdueWork()!!.state
        })
        assertTrue(mum.checkIns.recent(days = 30000).first().none { it.checkIn.status == CheckInStatus.OVERDUE })

        traveller.checkIns.cancelTrip()
        assertEquals(TripStatus.CANCELLED, traveller.checkIns.tripById(trip.id)!!.status)
        mum.await(CheckInStatus.CANCELLED)
    }
}
