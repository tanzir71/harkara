package io.github.tanzir71.harkara.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.data.PinEntity
import io.github.tanzir71.harkara.relay.RoomEventStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSearchTest {
    private lateinit var db: HarkaraDatabase
    private lateinit var search: LocalSearch

    @Before fun setup() {
        db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        search = LocalSearch(db) { NOW }
    }

    @After fun close() { db.close() }

    @Test fun `local FTS finds Bangla private pins and cached posts without a network`() = runBlocking {
        db.pinDao().upsert(pin("one", "সেতু", "বিশুদ্ধ পানি"))
        val post = KeyPair.generate().signEvent(Kinds.FEED_POST, listOf(listOf("g", "wh0r0d")), "Water near the bridge", NOW)
        assertTrue(RoomEventStore(db).store(post, Carrier.NODE, own = false))
        val pin = search.search("সেতু").single()
        assertTrue(pin.pin)
        assertEquals("সেতু", pin.title)
        assertEquals(23.75, pin.position!!.lat, .00001)
        val found = search.search("water bridge").single()
        assertEquals(post.id, found.id)
        assertFalse(found.pin)
        assertEquals(post.content, found.text)
        assertNotNull(found.position)
    }

    @Test fun `rebuild removes deleted and expired results without deleting source data`() = runBlocking {
        val active = pin("active", "Clinic", "Medicine")
        db.pinDao().upsert(active)
        db.pinDao().upsert(pin("expired", "Clinic", "Old", expires = NOW - 1))
        db.pinDao().upsert(pin("deleted", "Clinic", "Old").copy(deleted = true))
        assertEquals(listOf("active"), search.search("clinic").map { it.id })
        db.pinDao().upsert(active.copy(title = "School", note = "Books", updatedAt = NOW + 1))
        assertTrue(search.search("clinic").isEmpty())
        assertEquals("active", search.search("books").single().id)
        assertNotNull(db.pinDao().get("expired"))
        assertTrue(db.pinDao().get("deleted")!!.deleted)
    }

    @Test fun `empty punctuation and FTS operators are bounded literal input`() = runBlocking {
        db.pinDao().upsert(pin("one", "Bridge", "Open"))
        for (query in listOf("", "   ", "\"*():;", "OR NOT NEAR", "bridge\" OR 1=1 --", "x".repeat(10_000))) {
            assertTrue(search.search(query).size <= 50)
        }
        assertEquals(1, search.search("bridge").size)
        assertEquals(1, search.search("bridge").size)
    }

    private fun pin(id: String, title: String, note: String, expires: Long? = null) = PinEntity(
        address = id, id = id, owner = "a".repeat(64), visibility = "PRIVATE", title = title, note = note,
        lat = 23.75, lng = 90.38, color = "teal", createdAt = NOW, expiresAt = expires, deleted = false,
        recipients = "", eventId = id, geohash5 = "wh0r0", json = "{}", own = true, updatedAt = NOW,
    )

    private companion object { const val NOW = 1_795_590_000L }
}
