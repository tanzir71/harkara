package io.github.tanzir71.harkara.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
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

/** The grey-area switches ask first (cost + permission) and explain restricted settings when Android says no. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class SmsUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val platform = SmsPlatform()

    private class SmsPlatform : PlatformActions by PlatformActions.None {
        var grant = false
        var asked = 0
        var appInfo = 0
        val sms = mutableListOf<String>()
        override fun requestSmsPermissions(onResult: (granted: Boolean) -> Unit) {
            asked++
            onResult(grant)
        }
        override fun openAppInfo() {
            appInfo++
        }
        override fun composeSms(body: String) {
            sms += body
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container = AppContainer(context, inMemoryDatabase = true)
        compose.setContent { HarkaraApp(container, platform) }
        compose.onNodeWithText("Dhanmondi").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openModules() {
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("All modules").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `turning on Send SMS directly shows the cost, and a refusal leads to the restricted-settings steps`() {
        openModules()
        compose.onNodeWithText("Send SMS directly").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Send SMS directly?").assertIsDisplayed()
        compose.onNodeWithText("Cost: 1–2 SMS from your credit per message").assertIsDisplayed()
        assertEquals(0, platform.asked)

        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertEquals(1, platform.asked)
        assertFalse("no permission, no switch", container.settings.state.value.isOn(Module.SMS_DIRECT))
        compose.onNodeWithText("Allow SMS for Harkara").assertIsDisplayed()
        compose.onNodeWithText("Tap ⋮ at the top right and choose Allow restricted settings. Confirm with your PIN or fingerprint.").assertIsDisplayed()
        compose.onNodeWithText("Open App info").performClick()
        assertEquals(1, platform.appInfo)
    }

    @Test
    fun `with permission the gateway turns on and opens its page, and Not now changes nothing`() {
        openModules()
        compose.onNodeWithText("Volunteer SMS gateway").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertFalse(container.settings.state.value.isOn(Module.SMS_GATEWAY))

        platform.grant = true
        compose.onNodeWithText("Volunteer SMS gateway").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cost: up to 100 SMS a day from your credit").assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertTrue(container.settings.state.value.isOn(Module.SMS_GATEWAY))
        compose.onNodeWithText("0 of 100 SMS relayed today").assertIsDisplayed()
    }

    @Test
    fun `a link handed to the app is verified and lands where it belongs`() {
        val keys = KeyPair.generate()
        val checkIn = keys.signEvent(Kinds.CHECK_IN, listOf(listOf("g", "wh0r0dkq7")), "Reached the hall", System.currentTimeMillis() / 1000)
        container.pendingLinks.value = HarkaraLinks.parse(HarkaraLinks.web(HarkaraLinks.eventRest(checkIn)))
        compose.waitUntil(5_000) { container.pendingLinks.value == null }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Received and verified").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Received and verified").assertIsDisplayed()
        // Let the snackbar go before tapping rows underneath it.
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        // Pasting junk into Settings → "Open a link, QR text or SMS" explains instead of failing silently.
        compose.onNodeWithText("Me").performClick()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Open a link, QR text or SMS").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("What someone sent you").performTextInput("hello there")
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Couldn't verify this. It may be damaged, or from someone this phone doesn't know yet.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Couldn't verify this. It may be damaged, or from someone this phone doesn't know yet.").assertIsDisplayed()
        assertNull(container.pendingLinks.value)
        assertTrue(platform.sms.isEmpty())
        assertTrue(container.pendingLinks.value !is HarkaraLink)
    }
}
