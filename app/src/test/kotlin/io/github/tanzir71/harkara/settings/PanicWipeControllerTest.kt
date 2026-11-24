package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.PostEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanicWipeControllerTest {
    @Test
    fun `panic wipe clears room database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HarkaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.postDao().upsert(
            PostEntity(
                postId = "p1",
                author = "nearid",
                displayName = "Aster",
                text = "wipe me",
                geohash = "9q8yyk",
                kind = "post",
                imageRef = null,
                createdAt = 1,
                expiresAt = 10_000,
                signature = ByteArray(64),
            ),
        )

        assertEquals(1, db.postDao().observeFresh(now = 2).first().size)
        PanicWipeController.panicWipe(db)

        assertEquals(0, db.postDao().observeFresh(now = 2).first().size)
        db.close()
    }
}
