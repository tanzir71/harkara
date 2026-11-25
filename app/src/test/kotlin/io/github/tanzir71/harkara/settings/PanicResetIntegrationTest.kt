package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.core.location.LiveDuration
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.DatabaseMigrations
import io.github.tanzir71.harkara.data.EventEntity
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.places.LiveShareHost
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RelaySyncWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Plain SQLite stands in for SQLCipher; model the same missing-key/new-file boundary without JNI. */
class PanicResetTestApplication : HarkaraApplication() {
    val containers = mutableListOf<AppContainer>()
    val databases = mutableListOf<HarkaraDatabase>()
    private var generation = 0

    override fun createContainer(): AppContainer = newPlainContainer()

    fun newPlainContainer(): AppContainer = AppContainer(this, databaseFactory = {
        val key = File(noBackupFilesDir, "db.key")
        if (!key.exists()) {
            deleteDatabase(HarkaraDatabase.NAME)
            key.parentFile!!.mkdirs()
            key.writeBytes(ByteArray(32) { (++generation).toByte() })
        }
        Room.databaseBuilder(this, HarkaraDatabase::class.java, HarkaraDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build().also { databases += it }
    }).also { containers += it }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PanicResetTestApplication::class)
class PanicResetIntegrationTest {
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val app = ApplicationProvider.getApplicationContext<PanicResetTestApplication>()

    @Before
    fun setUp() {
        app.container.settings.clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() = runBlocking {
        app.containers.forEach { it.scope.coroutineContext[Job]!!.cancelAndJoin() }
        app.databases.forEach { it.close() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun event(id: String) = EventEntity(
        id, "author", 1, 1, "{\"private\":\"$id\"}", "wh0r0d", "wh0r0", "chatter", null,
        null, 1, 32, own = true, carrier = "SAVED",
    )

    @Test
    fun `wipe retires old DB and identity then fresh onboarding survives a later reopen`() = runBlocking {
        val old = app.container
        old.settings.completeOnboarding("Banani", false)
        val oldDatabase = old.database
        val oldDao = oldDatabase.eventDao()
        oldDao.insert(event("old"))
        val oldKey = File(app.noBackupFilesDir, "db.key").readBytes()
        val oldIdentity = old.identity.profile().pubkeyHex
        val oldLan = old.lanRoom
        val oldInbox = old.giftInbox
        val oldLive = old.liveShareRepository
        val oldFile = File(app.filesDir, "maps/private.pmtiles").apply { parentFile!!.mkdirs(); writeText("old map") }

        old.panicWipe()

        assertTrue(old.isWiped)
        assertFalse(oldDatabase.isOpen)
        assertFalse(app.getDatabasePath(HarkaraDatabase.NAME).exists())
        assertFalse(File(app.noBackupFilesDir, "db.key").exists())
        assertFalse(AppContainer.seedFile(app).exists())
        assertFalse(oldFile.exists())
        assertTrue(runCatching { oldDao.insert(event("late-old-write")) }.isFailure)
        assertThrows(IllegalStateException::class.java) { old.database }
        assertThrows(IllegalStateException::class.java) { old.identity }
        assertThrows(IllegalStateException::class.java) { old.startRelays() }

        val fresh = app.resetContainer()
        assertNotSame(old, fresh)
        assertNotSame(old.scope, fresh.scope)
        assertNotSame(oldLan, fresh.lanRoom)
        assertNotSame(oldInbox, fresh.giftInbox)
        assertNotSame(oldLive, fresh.liveShareRepository)
        assertNotSame(oldDatabase, fresh.database)
        assertTrue(fresh.scope.coroutineContext[Job]!!.isActive)
        assertFalse(fresh.settings.state.value.onboarded)
        assertNull(fresh.database.eventDao().get("old"))
        assertNull(fresh.database.eventDao().get("late-old-write"))
        val freshIdentity = fresh.identity.profile().pubkeyHex
        assertNotEquals(oldIdentity, freshIdentity)
        val freshKey = File(app.noBackupFilesDir, "db.key").readBytes()
        assertFalse(oldKey.contentEquals(freshKey))
        fresh.settings.completeOnboarding("Dhanmondi", false)
        fresh.database.eventDao().insert(event("new-after-onboarding"))
        // A late duplicate callback on the retired graph must not erase the replacement's new files.
        old.panicWipe()
        assertArrayEquals(freshKey, File(app.noBackupFilesDir, "db.key").readBytes())
        assertEquals("new-after-onboarding", fresh.database.eventDao().get("new-after-onboarding")!!.id)
        fresh.scope.coroutineContext[Job]!!.cancelAndJoin()
        fresh.database.close()

        val reopened = app.newPlainContainer()
        assertEquals(freshIdentity, reopened.identity.profile().pubkeyHex)
        assertEquals("new-after-onboarding", reopened.database.eventDao().get("new-after-onboarding")!!.id)
        assertArrayEquals(freshKey, File(app.noBackupFilesDir, "db.key").readBytes())
        assertTrue(reopened.settings.state.value.onboarded)
        assertNull(reopened.database.eventDao().get("old"))
    }

    @Test
    fun `wipe joins old writers stops carriers and live share and cancels all scheduled work`() = runBlocking {
        val old = app.container
        val oldDatabase = old.database
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        old.scope.launch {
            try {
                cleanupStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    oldDatabase.eventDao().insert(event("writer-finally"))
                    File(app.cacheDir, "writer-finally").writeText("must be erased after this job joins")
                    cleanupFinished.complete(Unit)
                }
            }
        }
        cleanupStarted.await()
        val delayed = OneTimeWorkRequestBuilder<RelaySyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(app).enqueue(delayed).result.get()
        var liveStopped = false
        val live = old.liveShareRepository
        live.host = object : LiveShareHost { override fun stopped() { liveStopped = true } }
        live.start(listOf(KeyPair.generate().pubkeyHex), LiveDuration.MIN_15)
        old.lanRoom.start()
        val relay = FakeNostrRelay().start()
        try {
            old.relayPool.setRelays(listOf(relay.url))
            withTimeout(10_000) { old.relayPool.status.first { it.values.any { status -> status == RelayStatus.CONNECTED } } }

            old.panicWipe()

            assertTrue(cleanupFinished.isCompleted)
            assertTrue(old.scope.coroutineContext[Job]!!.isCompleted)
            assertFalse(File(app.cacheDir, "writer-finally").exists())
            assertFalse(app.getDatabasePath(HarkaraDatabase.NAME).exists())
            assertFalse(old.lanRoom.running.value)
            assertEquals(0, old.relayPool.connectedCount)
            assertNull(live.outgoing.value)
            assertTrue(liveStopped)
            assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(app).getWorkInfoById(delayed.id).get()!!.state)
        } finally {
            old.relayPool.stop()
            old.lanRoom.stop()
            relay.shutdown()
        }
    }

    @Test
    fun `reset refuses to cross the boundary before destructive work completes`() {
        val old = app.container
        assertThrows(IllegalStateException::class.java) { app.resetContainer() }
        assertTrue(app.container === old)
        assertTrue(old.scope.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun `full wipe closes and removes old database before erasing key material`() = runBlocking {
        val db = app.container.database
        db.eventDao().insert(event("keep-until-close"))
        val key = File(app.noBackupFilesDir, "db.key")
        val legacy = app.openOrCreateDatabase("Harkara.db", Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE legacy_private (value TEXT)")
        legacy.close()
        var checkedOrder = false

        PanicWipeController.wipeEverything(app, db, app.container.settings) {
            assertFalse(db.isOpen)
            assertFalse(app.getDatabasePath(HarkaraDatabase.NAME).exists())
            assertFalse(app.getDatabasePath("Harkara.db").exists())
            assertTrue(key.exists())
            checkedOrder = true
        }

        assertTrue(checkedOrder)
        assertFalse(key.exists())
        assertTrue(runCatching { db.eventDao().insert(event("stale")) }.isFailure)
        assertFalse(app.container.settings.state.value.onboarded)
    }
}
