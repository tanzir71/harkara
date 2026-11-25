package io.github.tanzir71.harkara.ui

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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M9 wiring in the real app shell: Nearby map (Lite without a pack), My pins, Map packs, Compass, Modules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class PlacesAppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container = AppContainer(context, inMemoryDatabase = true)
        container.mapPackManager.delete()
        compose.setContent { HarkaraApp(container, PlatformActions.None) }
        compose.onNodeWithText("Dhanmondi").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `map mode without a pack is the Lite list with a way to get the map`() {
        compose.onNodeWithText("Map").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Offline map of Dhaka").assertIsDisplayed()
        compose.onNodeWithText("Map packs").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dhaka map pack").assertIsDisplayed()
        compose.onNodeWithText("Find the latest pack").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `tools open My pins and Compass`() {
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("My pins").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No pins yet").assertIsDisplayed()
        compose.onNodeWithText("Check-ins").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No check-ins yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Compass").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The compass sensor and GPS run only while this screen is open.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `offline map module stays off until a pack is downloaded`() {
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("All modules").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Offline map pack").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dhaka map pack").assertIsDisplayed()
        assertFalse(container.settings.state.value.isOn(Module.OFFLINE_MAP))
    }
}
