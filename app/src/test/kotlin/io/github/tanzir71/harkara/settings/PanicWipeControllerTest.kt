package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.EventEntity
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
        val db = db()
        db.seed()
        val settings = AppSettings(context)
        settings.completeOnboarding("Banani", auto = false)
        val secret = File(context.filesDir, "keys/root.bin").apply { parentFile!!.mkdirs(); writeText("seed") }
        val cached = File(context.cacheDir, "media.tmp").apply { writeText("x") }

        PanicWipeController.wipeEverything(context, db, settings)

        assertEquals(0, db.count())
        assertFalse(settings.state.value.onboarded)
        assertFalse(secret.exists())
        assertFalse(cached.exists())
        db.close()
    }
}
