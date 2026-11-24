package io.github.tanzir71.harkara.ui.feed

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.PostEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRoomRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `feed ui renders posts from room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, HarkaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.postDao().upsert(
            PostEntity(
                postId = "p1",
                author = "nearid",
                displayName = "Aster",
                text = "Water available at school",
                geohash = "9q8yyk",
                kind = "post",
                imageRef = null,
                createdAt = 2_000,
                expiresAt = 10_000,
                signature = ByteArray(64),
            ),
        )

        val posts = db.postDao().observeFresh(now = 3_000).first()
        compose.setContent { FeedPostList(posts = posts) }

        compose.onNodeWithText("Water available at school").assertIsDisplayed()
        db.close()
    }
}
