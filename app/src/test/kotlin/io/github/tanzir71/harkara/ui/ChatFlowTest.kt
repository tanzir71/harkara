package io.github.tanzir71.harkara.ui

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.identity.shortNpub
import io.github.tanzir71.harkara.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Chats tab end to end on one phone: rooms, New chat, the name prompt, sending offline, long-press. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class ChatFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val copied = mutableListOf<String>()
    private val platform = object : PlatformActions {
        override fun openUrl(url: String) = Unit
        override fun shareText(text: String) = Unit
        override fun copyToClipboard(label: String, text: String) {
            copied += text
        }
        override fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit) = onResult(23.7465, 90.376)
        override fun applyLanguage() = Unit
        override fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit) = onResult(false)
        override fun openTextDocument(onResult: (text: String?) -> Unit) = onResult(null)
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
        compose.onNodeWithText("Chats").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `new chat by key, name asked once, message saved offline, long-press offers make it a task`() {
        compose.onNodeWithText("Everyone on this Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("No chats yet").assertIsDisplayed()

        val bob = Bech32.encodeNpub(KeyPair.generate().pubkeyHex)
        compose.onNodeWithText("New chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Their key (npub1…)").performTextInput("not a key")
        compose.onNodeWithText("Start chat").performClick()
        compose.onNodeWithText("That isn't a Harkara or Nostr key (npub1…)").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(container.identity.npub(), copied.single())

        // Start over with a real key.
        compose.onNodeWithContentDescription("Their key (npub1…)").performTextClearance()
        compose.onNodeWithContentDescription("Their key (npub1…)").performTextInput(bob)
        compose.onNodeWithText("Start chat").performClick()
        val hint = "Message · sends when a route opens"
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(hint)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(shortNpub(bob))).onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription(hint).performTextInput("Hello from the hall")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("What should people call you?").assertIsDisplayed()
        compose.onNodeWithText("Send without a name").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Hello from the hall")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Saved on this phone").assertIsDisplayed()
        assertTrue(container.chatRepository.prefs.nameAsked)

        // The second message is not asked about again.
        compose.onNodeWithContentDescription(hint).performTextInput("Second one")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Second one")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Hello from the hall").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Make it a task").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Coming soon")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Messages")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Second one")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Second one").assertIsDisplayed()
        val rows = runBlocking { container.chatRepository.conversations().first() }
        assertEquals(1, rows.size)
    }

    @Test
    fun `the Wi-Fi room and area channel open from the list`() {
        compose.onNodeWithText("Everyone on this Wi-Fi").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Just you so far")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Message · sends when a route opens").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dhanmondi channel").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Area channel · anonymous")).fetchSemanticsNodes().isNotEmpty() }
    }
}
