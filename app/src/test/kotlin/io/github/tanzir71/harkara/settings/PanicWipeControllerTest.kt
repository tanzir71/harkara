package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.EventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanicWipeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun db() = HarkaraDatabase.inMemory(context)

    private suspend fun HarkaraDatabase.seed() = eventDao().insert(
        EventEntity("p1", "author", 1, 1, "{}", "wh0k8b", "wh0k8", "chatter", null, null, 1, 2, own = true, carrier = "SAVED"),
    )

    private suspend fun HarkaraDatabase.count() = eventDao().storedRows().size

    @Test
    fun `panic wipe clears room database`() = runBlocking {
        val db = db()
        db.seed()
        assertEquals(1, db.count())

        PanicWipeController.panicWipe(db)

        assertEquals(0, db.count())
        db.close()
    }

    @Test
    fun `wipe everything clears database settings and files`() = runBlocking {
        fun open() = Room.databaseBuilder(context, HarkaraDatabase::class.java, HarkaraDatabase.NAME)
            .allowMainThreadQueries().build()
        val db = open()
        db.seed()
        assertEquals(1, db.count())
        val settings = AppSettings(context)
        settings.completeOnboarding("Banani", auto = false)
        val secret = File(context.filesDir, "keys/root.bin").apply { parentFile!!.mkdirs(); writeText("seed") }
        val cached = File(context.cacheDir, "media.tmp").apply { writeText("x") }
        val key = File(context.noBackupFilesDir, "db.key").apply { writeBytes(ByteArray(32) { 7 }) }
        assertTrue(context.getDatabasePath(HarkaraDatabase.NAME).exists())
        var closedBeforeKeyErasure = false

        PanicWipeController.wipeEverything(context, db, settings) {
            assertFalse(db.isOpen)
            assertFalse(context.getDatabasePath(HarkaraDatabase.NAME).exists())
            assertTrue(key.exists())
            closedBeforeKeyErasure = true
        }

        assertTrue(closedBeforeKeyErasure)
        assertFalse(db.isOpen)
        assertTrue(runCatching { db.seed() }.isFailure)
        assertFalse(context.getDatabasePath(HarkaraDatabase.NAME).exists())
        assertFalse(settings.state.value.onboarded)
        assertFalse(secret.exists())
        assertFalse(cached.exists())
        assertFalse(key.exists())
        val fresh = open()
        try {
            assertEquals(0, fresh.count())
            fresh.seed()
            assertEquals(1, fresh.count())
        } finally {
            fresh.close()
        }
        val reopened = open()
        try { assertEquals(1, reopened.count()) } finally { reopened.close() }
    }
}
