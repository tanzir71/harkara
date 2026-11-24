package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.PostEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanicWipeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun db() = Room.inMemoryDatabaseBuilder(context, HarkaraDatabase::class.java).allowMainThreadQueries().build()

    private suspend fun HarkaraDatabase.seed() = postDao().upsert(
        PostEntity("p1", "author", "Aster", "wipe me", "wh0k8b", "post", null, 1, 10_000, ByteArray(64)),
    )

    @Test
    fun `panic wipe clears room database`() = runBlocking {
        val db = db()
        db.seed()
        assertEquals(1, db.postDao().observeFresh(now = 2).first().size)

        PanicWipeController.panicWipe(db)

        assertEquals(0, db.postDao().observeFresh(now = 2).first().size)
        db.close()
    }

    @Test
    fun `wipe everything clears database settings and files`() = runBlocking {
        val db = db()
        db.seed()
        val settings = AppSettings(context)
        settings.completeOnboarding("Banani", auto = false)
        val secret = File(context.filesDir, "keys/root.bin").apply { parentFile!!.mkdirs(); writeText("seed") }
        val cached = File(context.cacheDir, "media.tmp").apply { writeText("x") }

        PanicWipeController.wipeEverything(context, db, settings)

        assertEquals(0, db.postDao().observeFresh(now = 2).first().size)
        assertFalse(settings.state.value.onboarded)
        assertFalse(secret.exists())
        assertFalse(cached.exists())
        db.close()
    }
}
