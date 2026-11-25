package io.github.tanzir71.harkara.ui

import kotlinx.coroutines.cancelAndJoin

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.settings.AppSettings
import io.github.tanzir71.harkara.settings.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val platform = RecordingPlatform()
    private val activeContainer = androidx.compose.runtime.mutableStateOf<AppContainer?>(null)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container = AppContainer(context, inMemoryDatabase = true)
        activeContainer.value = container
        platform.afterWipe = {
            container = AppContainer(context, inMemoryDatabase = true)
            activeContainer.value = container
        }
        compose.setContent { activeContainer.value?.let { current -> androidx.compose.runtime.key(current) { HarkaraApp(current, platform) } } }
    }

    private fun pickArea(name: String = "Dhanmondi") {
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @org.junit.After
    fun tearDown() = kotlinx.coroutines.runBlocking {
        activeContainer.value = null
        compose.waitForIdle()
        container.stopRelays()
        container.node.stopSharing()
        container.node.stopNode(keepRoom = false)
        container.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        container.database.close()
        androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `area chip goes straight to the nearby feed with an honest saved pill`() {
        compose.onNodeWithText("Where are you?").assertIsDisplayed()

        pickArea()

        compose.onNodeWithText("Dhanmondi").assertIsDisplayed()
        compose.onNodeWithText("Within 3 km · chosen by you").assertIsDisplayed()
        compose.onNodeWithContentDescription("Saved, sends later").assertIsDisplayed()
        compose.onNodeWithText("Nothing nearby yet").assertIsDisplayed()
        assertEquals("Dhanmondi", container.settings.state.value.areaName)
    }

    @Test
    fun `location button is optional and falls back to near you`() {
        compose.onNodeWithText("Use my location").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Near you").assertIsDisplayed()
        assertTrue(container.settings.state.value.areaAuto)
        assertNull(container.settings.state.value.areaName)
    }

    @Test
    fun `chosen area can be changed after onboarding`() {
        pickArea()
        compose.onNodeWithText("Dhanmondi").performClick()
        compose.onNodeWithText("Choose your area").assertIsDisplayed()
        compose.onNodeWithText("Banani").performScrollTo().performClick()
        compose.onNodeWithText("Banani").assertIsDisplayed()
        assertEquals("Banani", container.settings.state.value.areaName)
        assertFalse(container.settings.state.value.areaAuto)
    }

    @Test
    fun `nearby search opens an offline local search rather than a placeholder`() {
        pickArea()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(io.github.tanzir71.harkara.R.string.nearby_search)).performClick()
        compose.onNodeWithText("Search this phone").assertIsDisplayed()
        compose.onNodeWithText("Place, pin or nearby post").assertIsDisplayed()
    }

    @Test
    fun `connectivity sheet opens from the pill`() {
        pickArea()
        compose.onNodeWithContentDescription("Saved, sends later").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("How messages get out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Internet relays").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `modules toggle from settings and persist`() {
        pickArea()
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("All modules").performScrollTo().performClick()
        compose.waitForIdle()

        assertFalse(container.settings.state.value.isOn(Module.NODE))
        compose.onNodeWithText("Neighbourhood node").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(container.settings.state.value.isOn(Module.NODE))
        assertTrue(AppSettings(ApplicationProvider.getApplicationContext<Context>()).state.value.isOn(Module.NODE))
    }

    @Test
    fun `triple tap on the logo offers panic wipe and wiping returns to onboarding`() {
        pickArea()
        val retired = container
        compose.onNodeWithText("Me").performClick()
        val logo = compose.onNodeWithContentDescription("Harkara logo")
        repeat(3) { logo.performClick() }
        compose.waitForIdle()

        compose.onNodeWithText("Wipe everything now?").assertIsDisplayed()
        compose.onNodeWithText("Wipe now").performClick()
        compose.waitUntil(10_000) { container !== retired }
        compose.waitForIdle()

        assertTrue(retired.isWiped)
        compose.onNodeWithText("Where are you?").assertIsDisplayed()
    }

    @Test
    fun `copy crash report puts the report on the clipboard`() {
        pickArea()
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Copy crash report").performClick()
        compose.waitForIdle()

        assertTrue(platform.copied!!.startsWith("Harkara "))
        compose.onNodeWithText("Share this app").performClick()
        compose.onNodeWithText("Share the online install link").performClick()
        assertTrue(platform.shared!!.contains("https://tanzir71.github.io/harkara/"))
    }

    private class RecordingPlatform : PlatformActions {
        var afterWipe: () -> Unit = {}
        override fun afterPanicWipe() = afterWipe()
        var copied: String? = null
        var shared: String? = null
        override fun openUrl(url: String) = Unit
        override fun shareText(text: String) {
            shared = text
        }
        override fun copyToClipboard(label: String, text: String) {
            copied = text
        }
        override fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit) = onResult(null, null)
        override fun applyLanguage() = Unit
        override fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit) = onResult(false)
        override fun openTextDocument(onResult: (text: String?) -> Unit) = onResult(null)
    }
}
