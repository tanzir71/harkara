package io.github.tanzir71.harkara.feed

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.feed.PostComposer
import io.github.tanzir71.harkara.core.feed.WordFilters
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.data.DhakaAreas
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Gate for M4: the feed renders from Room with no network at all (no relay is ever configured). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class FeedOfflineRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `cached posts render offline with distance, time left and how they travelled`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = 1_764_000_000L
        val clock = { now * 1000 }
        val db = HarkaraDatabase.inMemory(context)
        val pool = RelayPool(CoroutineScope(Dispatchers.IO))
        val store = RoomEventStore(db, clock)
        val repo = FeedRepository(db, store, Outbox(db, pool, clock), pool, WordFilters.parse(File("../data/word-filters/filters.json").readText()), { KeyPair.generate() }, nowMillis = clock)
        val center = LatLng(23.7465, 90.376)
        val post = PostComposer.compose(KeyPair.generate(), "Octane available at the pump on Satmasjid Road", Category.STOCK, 23.7470, 90.3790, now - 25 * 60,
            mapOf("stock" to "fuel", "status" to "available"), powBits = 16)
        val far = PostComposer.compose(KeyPair.generate(), "Uttara is far away", Category.CHATTER, 23.8744, 90.3992, now, powBits = 16)
        repo.ingest(post, Carrier.MESH)
        repo.ingest(far, Carrier.ONLINE)

        val items = repo.feed(center).first()
        assertEquals(listOf(post.id), items.map { it.post.id })
        assertEquals(0, pool.connectedCount)

        val areas = DhakaAreas.load(context)
        compose.setContent {
            val presenter = rememberFeedPresenter(areas)
            HarkaraTheme(night = false) {
                NearbyScreen("Dhanmondi", false, ConnectivityState.Saved, items.map { presenter.toUi(it, now) })
            }
        }
        compose.onNodeWithText("Octane available at the pump on Satmasjid Road").assertIsDisplayed()
        compose.onNodeWithText("Fuel · Available").assertIsDisplayed()
        compose.onNodeWithText("23 h left").assertIsDisplayed()
        compose.onNodeWithText("via mesh").assertIsDisplayed()
        compose.onNodeWithText("25 min", substring = true).assertIsDisplayed()
        db.close()
    }
}
