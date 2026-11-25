package io.github.tanzir71.harkara.tools

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.tools.SosEvents
import io.github.tanzir71.harkara.core.tools.SosStatus
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.relay.Outbox
import io.github.tanzir71.harkara.relay.RelayPool
import io.github.tanzir71.harkara.relay.RoomEventStore
import io.github.tanzir71.harkara.ui.screens.TorchScreen
import io.github.tanzir71.harkara.ui.screens.HandbookScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No real relay or hardware: signed remote events, concurrent taps, and lifecycle-driven fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-mdpi")
class SafetyRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db by lazy { HarkaraDatabase.inMemory(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = RelayPool(scope)
    private val keys = KeyPair.generate()
    private val at = System.currentTimeMillis() / 1000
    private val store by lazy { RoomEventStore(db) }
    private val sos by lazy { SosRepository(db, store, Outbox(db, pool), pool, { keys }, { at * 1000 }) }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    @Test fun `unknown status cannot hide an incoming SOS or unlock our cooldown`() = runBlocking<Unit> {
        val remote = KeyPair.generate()
        val alert = SosEvents.sos(remote, null, null, "Help", at - 60)
        // A signed but malformed area must not crash the incoming flow.
        val malformedArea = remote.signEvent(Kinds.SOS, listOf(listOf("g", "not-a-geohash")), "Help", at - 60)
        val own = SosEvents.sos(keys, null, null, "Help here", at - 60)
        listOf(malformedArea, own).forEach { store.store(it, Carrier.QR, own = it.pubkey == keys.pubkeyHex) }
        listOf(malformedArea, own).forEach {
            val signer = if (it.pubkey == keys.pubkeyHex) keys else remote
            store.store(signer.signEvent(Kinds.SOS_UPDATE, listOf(listOf("e", it.id), listOf("status", "seen")), "", at), Carrier.QR, own = it == own)
        }
        assertEquals(29, sos.waitMinutes())
        assertEquals(own.id, sos.activeOwn().first()?.id)
        val incoming = sos.incoming(LatLng(23.75, 90.38)).first().single()
        assertEquals(malformedArea.id, incoming.event.id)
        assertNull(incoming.distanceMeters)
        store.store(SosEvents.update(remote, malformedArea, SosStatus.RESOLVED, at), Carrier.QR, own = false)
        store.store(SosEvents.update(keys, own, SosStatus.FALSE_ALARM, at), Carrier.QR, own = true)
        assertTrue(sos.incoming(LatLng(23.75, 90.38)).first().isEmpty())
        assertEquals(0, sos.waitMinutes())
        assertNull(sos.activeOwn().first())
        assertFalse(SosEvents.closes(SosEvents.update(remote, alert, SosStatus.RESOLVED, at), malformedArea))
    }

    @Test fun `concurrent SOS taps create only one alert`() = runBlocking<Unit> {
        val results = (1..8).map { async(Dispatchers.Default) { sos.send("Help $it", null, null) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(1, db.eventDao().byKinds(listOf(Kinds.SOS)).size)
        assertEquals(30, sos.waitMinutes())
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h800dp-mdpi")
    fun `Bangla handbook uses translated group titles rather than English data ids`() {
        val handbook = Handbook.fromAssets(context)
        compose.setContent {
            HarkaraTheme { HandbookScreen(handbook.index.sections, handbook.index.emergencyNumbers, "bn", {}, {}, {}) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("শুরু"))
        compose.onNodeWithText("শুরু").assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("প্রাথমিক চিকিৎসা"))
        compose.onNodeWithText("প্রাথমিক চিকিৎসা").assertIsDisplayed()
        compose.onNodeWithText("First aid").assertDoesNotExist()
    }

    @Test fun `every light and sound mode stops on background and stays off on return`() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.STARTED }
        }
        val scheduler = TestCoroutineScheduler()
        val lightScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val torch = object : Torch {
            override val available = true
            var on = false
            override fun set(on: Boolean) { this.on = on }
        }
        val siren = object : Siren {
            var beeps = 0
            var stopped = true
            override fun beep(ms: Int) { beeps++; stopped = false }
            override fun stop() { stopped = true }
        }
        val lights = LightController(torch, siren, lightScope)
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HarkaraTheme {
                    if (visible.value) {
                        val mode by lights.mode.collectAsState()
                        val lit by lights.lit.collectAsState()
                        TorchScreen(mode, lit, true, lights::start, lights::stop, {})
                    }
                }
            }
        }
        try {
            LightMode.entries.filter { it != LightMode.OFF }.forEach { mode ->
                compose.runOnIdle { lights.start(mode); scheduler.runCurrent() }
                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
                compose.runOnIdle {
                    val beeps = siren.beeps
                    scheduler.advanceTimeBy(5000); scheduler.runCurrent()
                    assertEquals(LightMode.OFF, lights.mode.value)
                    assertFalse(torch.on)
                    assertFalse(lights.lit.value)
                    assertTrue(siren.stopped)
                    assertEquals(beeps, siren.beeps)
                    owner.lifecycle.currentState = Lifecycle.State.STARTED
                    assertEquals("returning requires an explicit restart", LightMode.OFF, lights.mode.value)
                }
            }
            compose.runOnIdle { lights.start(LightMode.TORCH); visible.value = false }
            compose.runOnIdle { assertFalse(torch.on); assertEquals(LightMode.OFF, lights.mode.value) }
        } finally {
            lights.stop()
            lightScope.cancel()
        }
    }
}
