package io.github.tanzir71.harkara.places

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.pins.PinFileFormat
import io.github.tanzir71.harkara.core.pins.PinVisibility
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gate for M9: pins sync between phones over a relay; private pins only for their owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinSyncTest {
    private val relay = FakeNostrRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phones = mutableListOf<Phone>()
    private val lake = LatLng(23.7465, 90.3760)

    inner class Phone(val keys: KeyPair = KeyPair.generate()) {
        val db = HarkaraDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool)
        val inbox = GiftInbox({ keys })
        private val areaKeys = mutableMapOf<String, KeyPair>()
        val pins = PinRepository(db, outbox, pool, { keys }, { g5 -> synchronized(areaKeys) { areaKeys.getOrPut(g5) { KeyPair.generate() } } })

        init {
            outbox.start(scope)
            pins.startIngest(scope, inbox)
            inbox.start(scope, pool)
        }

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
            inbox.subscribe(pool)
            pins.subscribe(lake)
        }

        suspend fun awaitPins(predicate: (List<PinView>) -> Boolean): List<PinView> =
            withTimeout(15_000) { pins.pins().first(predicate) }
    }

    private fun phone(keys: KeyPair = KeyPair.generate()) = Phone(keys).also { phones += it }

    @Before
    fun setUp() {
        relay.start()
    }

    @After
    fun tearDown() {
        phones.forEach { it.pool.stop(); it.db.close() }
        scope.cancel()
        relay.shutdown()
    }

    @Test
    fun `public pin replaces on edit and still-there answers tally on every phone`() = runBlocking<Unit> {
        val tania = phone()
        val rafi = phone()
        val nabila = phone()
        tania.online(); rafi.online(); nabila.online()

        val pin = tania.pins.save("Water point", "Tubewell behind the lake mosque", lake, Category.STOCK, PinVisibility.PUBLIC)
        val atRafi = rafi.awaitPins { list -> list.any { it.pin.title == "Water point" } }.single()
        assertFalse(atRafi.mine)
        assertEquals(tania.keys.pubkeyHex, atRafi.pin.owner)

        // Tania edits the same pin: everyone keeps only the newer version.
        delay(1_100)
        tania.pins.save("Water point (open 7-10)", "Tubewell behind the lake mosque", lake, Category.STOCK, PinVisibility.PUBLIC, id = pin.id)
        rafi.awaitPins { list -> list.singleOrNull()?.pin?.title == "Water point (open 7-10)" }
        nabila.awaitPins { list -> list.singleOrNull()?.pin?.title == "Water point (open 7-10)" }

        // Still there? Rafi yes, Nabila no, then Rafi changes his mind.
        val seen = rafi.pins.pins().first().single().pin
        rafi.pins.answer(seen, true)
        nabila.pins.answer(nabila.pins.pins().first().single().pin, false)
        val tally = tania.awaitPins { list -> list.singleOrNull()?.tally?.let { it.yes == 1 && it.no == 1 } == true }.single().tally
        assertEquals(1, tally.yes)
        delay(1_100)
        rafi.pins.answer(seen, false)
        tania.awaitPins { list -> list.singleOrNull()?.tally?.let { it.yes == 0 && it.no == 2 } == true }
        assertEquals(false, rafi.awaitPins { it.single().tally.mine == false }.single().tally.mine)
        // Answers are signed by the weekly area key, not the profile key.
        assertTrue(relay.events.filter { it.kind == Kinds.REACTION }.none { it.pubkey == rafi.keys.pubkeyHex })

        // Deleting publishes a newer, deleted version: the pin disappears everywhere.
        delay(1_100)
        tania.pins.delete(tania.pins.pins().first().single().pin)
        rafi.awaitPins { it.isEmpty() }
        tania.awaitPins { it.isEmpty() }
    }

    @Test
    fun `shared pin reaches only the named people, private pin only its owner`() = runBlocking<Unit> {
        val tania = phone()
        val rafi = phone()
        val stranger = phone()
        tania.online(); rafi.online(); stranger.online()

        tania.pins.save("Flood shelter", "Govt. school, 2nd floor", lake, Category.ALERT, PinVisibility.SHARED, recipients = listOf(rafi.keys.pubkeyHex))
        tania.pins.save("Home gate", "Blue gate", lake, Category.FOOD, PinVisibility.PRIVATE)

        val atRafi = rafi.awaitPins { list -> list.any { it.pin.title == "Flood shelter" } }
        assertEquals(PinVisibility.SHARED, atRafi.single().pin.visibility)
        assertTrue(atRafi.none { it.pin.title == "Home gate" })

        // Tania's second phone (same key, empty database) gets both back from the relay, decrypted.
        val taniaAgain = phone(tania.keys)
        taniaAgain.online()
        val restored = taniaAgain.awaitPins { list -> list.map { it.pin.title }.toSet() == setOf("Flood shelter", "Home gate") }
        assertTrue(restored.all { it.mine })
        assertEquals("Blue gate", restored.single { it.pin.visibility == PinVisibility.PRIVATE }.pin.note)

        // Nothing readable on the relay, nothing at all for a stranger.
        assertTrue(relay.events.none { it.content.contains("shelter") || it.content.contains("gate") })
        assertTrue(relay.events.any { it.kind == Kinds.APP_DATA && it.pubkey == tania.keys.pubkeyHex })
        delay(1_000)
        assertTrue(stranger.pins.pins().first().isEmpty())
        // A stranger who asks for Tania's app data gets ciphertext it can't open.
        val privateEvent = relay.events.first { it.kind == Kinds.APP_DATA }
        assertFalse(stranger.pins.ingest(privateEvent))
        assertNull(stranger.pins.pins().first().firstOrNull())
    }

    @Test
    fun `gpx import becomes private pins and exports back`() = runBlocking<Unit> {
        val tania = phone()
        val gpx = """<gpx xmlns="http://www.topografix.com/GPX/1/1"><wpt lat="23.81" lon="90.41"><name>Gulshan 1</name></wpt><wpt lat="23.75" lon="90.39"><name>Clinic</name><desc>Open 24 h</desc></wpt></gpx>"""
        assertEquals(2, tania.pins.importFile("places.gpx", gpx))
        val mine = tania.pins.pins().first()
        assertEquals(setOf("Gulshan 1", "Clinic"), mine.map { it.pin.title }.toSet())
        assertTrue(mine.all { it.pin.visibility == PinVisibility.PRIVATE && it.mine })
        val kml = tania.pins.export(PinFileFormat.KML)
        assertTrue(kml.contains("<name>Clinic</name>") && kml.contains("90.410000,23.810000"))
        assertEquals(0, tania.pins.importFile("notes.txt", "hello"))
    }
}
