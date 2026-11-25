package io.github.tanzir71.harkara.tools

import android.content.Context
import android.location.LocationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.tools.SosStatus
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.FakeNostrRelay
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.ui.HarkaraApp
import io.github.tanzir71.harkara.ui.PlatformActions
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/** Gate for M11: SOS rate limit + resolve, the 3-second hold, and modules that stay silent while off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class SafetyTest {
    @get:Rule
    val compose = createComposeRule()

    private val relay = FakeNostrRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val area = LatLng(23.7465, 90.376)

    inner class Phone {
        val keys = KeyPair.generate()
        val db = HarkaraDatabase.inMemory(context)
        val pool = RelayPool(scope)
        val outbox = Outbox(db, pool).also { it.start(scope) }
        val sos = SosRepository(db, RoomEventStore(db), outbox, pool, { keys }).also { it.startIngest(scope) }

        suspend fun online() {
            pool.setRelays(listOf(relay.url))
            withTimeout(10_000) { pool.status.first { it.values.any { s -> s == RelayStatus.CONNECTED } } }
            sos.subscribe(area)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { relay.shutdown() }
    }

    @Test
    fun `sos goes out at top priority, is limited to one per half hour, and resolves`() = runBlocking<Unit> {
        relay.start()
        val tania = Phone()
        val rafi = Phone()
        tania.online(); rafi.online()

        val first = tania.sos.send("Water rising at Road 7", LatLng(23.7470, 90.3790), "Tania")
        assertNotNull(first)
        assertNull(tania.sos.send("again", area, "Tania"))
        assertTrue(tania.sos.waitMinutes() in 29..30)
        assertEquals(first!!.id, tania.sos.activeOwn().first()?.id)

        val seen = withTimeout(10_000) { rafi.sos.incoming(area).first { it.isNotEmpty() } }.single()
        assertEquals("Water rising at Road 7", seen.event.content)
        assertTrue(seen.distanceMeters!! < 1000)

        tania.sos.resolve(first, SosStatus.RESOLVED)
        assertNull(tania.sos.activeOwn().first())
        assertEquals(0, tania.sos.waitMinutes())
        withTimeout(10_000) { rafi.sos.incoming(area).first { it.isEmpty() } }
        assertNotNull(tania.sos.send("new emergency", area, "Tania"))
    }

    @Test
    fun `holding the pill for 3 seconds raises SOS, a tap opens the sheet`() {
        var taps = 0
        var holds = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { HarkaraTheme { ConnectivityPill(Carrier.SAVED, onClick = { taps++ }, onLongClick = { holds++ }) } }
        val pill = compose.onNodeWithContentDescription("Saved, sends later")
        pill.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        pill.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0, holds)
        assertEquals("a short press is a tap", 1, taps)
        pill.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_200)
        pill.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, holds)
        assertEquals("a completed hold doesn't also open the sheet", 1, taps)
        compose.mainClock.autoAdvance = true
        pill.performClick()
        compose.waitForIdle()
        assertEquals(2, taps)
    }

    @Test
    fun `light controller stops everything when the screen leaves`() {
        val torch = FakeTorch()
        val siren = FakeSiren()
        val lights = LightController(torch, siren, scope)
        lights.start(LightMode.TORCH)
        assertTrue(torch.on)
        lights.start(LightMode.SIREN)
        assertFalse(torch.on)
        lights.stop()
        assertEquals(LightMode.OFF, lights.mode.value)
        assertFalse(torch.on)
        assertTrue(siren.stopped)
    }

    @Test
    fun `with default modules nothing runs in the background`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val container = AppContainer(context, inMemoryDatabase = true)
        container.settings.completeOnboarding("Dhanmondi", auto = false)
        assertFalse(container.settings.state.value.isOn(Module.MESH))
        assertFalse(container.settings.state.value.isOn(Module.LIVE_SHARE))
        assertFalse(container.settings.state.value.isOn(Module.NODE))
        compose.setContent { HarkaraApp(container, PlatformActions.None) }
        compose.onNodeWithText("Dhanmondi").assertIsDisplayed()

        val app = Shadows.shadowOf(context as android.app.Application)
        assertNull("no service may start while its module is off", app.nextStartedService)
        val location = Shadows.shadowOf(context.getSystemService(LocationManager::class.java))
        assertTrue("no GPS without a user-started share", location.locationUpdateListeners.isEmpty())
        assertEquals(0, container.relayPool.connectedCount)
    }

    @Test
    fun `handbook index and every page load in both languages`() {
        val handbook = Handbook { path -> File("../data/$path").readText() }
        val sections = handbook.index.sections
        assertTrue(sections.size >= 40)
        assertTrue(handbook.index.emergencyNumbers.any { it.number == "999" })
        sections.forEach { s ->
            assertTrue(handbook.page(s, "en").startsWith("#"))
            assertTrue(handbook.page(s, "bn").any { it in '\u0980'..'\u09FF' })
        }
    }

    private class FakeTorch : Torch {
        var on = false
        override val available = true
        override fun set(on: Boolean) {
            this.on = on
        }
    }

    private class FakeSiren : Siren {
        var stopped = false
        override fun beep(ms: Int) = Unit
        override fun stop() {
            stopped = true
        }
    }
}
