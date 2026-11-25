package io.github.tanzir71.harkara.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.core.keys.KeyRing
import io.github.tanzir71.harkara.core.nostr.Nip49
import io.github.tanzir71.harkara.identity.FileSeedStore
import io.github.tanzir71.harkara.identity.IdentityManager
import io.github.tanzir71.harkara.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Key backup and restore through the real screens, with the document picker faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class BackupFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private val platform = DocumentPlatform()

    @Before
    fun setUp() {
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        AppContainer.seedFile(context).delete()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        container = AppContainer(context, inMemoryDatabase = true)
        compose.setContent { HarkaraApp(container, platform) }
    }

    private fun openMe() {
        compose.onNodeWithText("Dhanmondi").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Me").performClick()
        compose.waitUntil(10_000) { container.identity.profilePubkey.value != null }
        compose.waitForIdle()
    }

    private fun enterPasswords(password: String, confirm: String = password) {
        compose.onNodeWithContentDescription("Password").performTextInput(password)
        compose.onNodeWithContentDescription("Password again").performTextInput(confirm)
        compose.waitForIdle()
    }

    @Test
    fun `me shows the short npub and the backup shows as a QR code`() {
        openMe()
        compose.onNode(hasText("npub1", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Back up key").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back up your key").assertIsDisplayed()
        enterPasswords("short", "short")
        compose.onNodeWithText("Use at least 8 characters").assertIsDisplayed()
        compose.onNodeWithText("Show as QR").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Password").performTextInput(" and long")
        compose.waitForIdle()
        compose.onNodeWithText("The passwords don't match").assertIsDisplayed()
        compose.onNodeWithContentDescription("Password again").performTextInput(" and long")
        compose.waitForIdle()
        compose.onNodeWithText("Show as QR").assertIsEnabled().performClick()

        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("QR code of your locked key backup")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Scan this on your new phone").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Scan this on your new phone")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `save to a file writes an ncryptsec that unlocks this phone's seed`() {
        openMe()
        compose.onNodeWithText("Back up key").performClick()
        compose.waitForIdle()
        enterPasswords("amar notun phone")

        compose.onNodeWithText("Save to a file…").performClick()
        compose.waitUntil(30_000) { platform.saved != null }
        compose.waitForIdle()

        assertEquals("harkara-key-backup.txt", platform.savedName)
        val backup = platform.saved!!
        assertTrue(backup.startsWith("ncryptsec1"))
        val restored = KeyRing(Nip49.decrypt(backup, "amar notun phone"))
        assertEquals(container.identity.profilePubkey.value, restored.profile().pubkeyHex)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Backup saved")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `onboarding restores a backup from a file`() {
        val elsewhere = IdentityManager(FileSeedStore(File(context.cacheDir, "other-phone/seed"), wrapper = null))
        val expected = elsewhere.profile().pubkeyHex
        platform.openText = elsewhere.exportBackup("purono phone") + "\n"

        compose.onNodeWithText("Restore from backup").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Restore your key").assertIsDisplayed()
        compose.onNodeWithText("Open a file…").performClick()
        compose.onNodeWithContentDescription("Password").performTextInput("wrong password")
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Wrong password or damaged backup")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Password").performTextReplacement("purono phone")
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntil(30_000) { container.identity.profilePubkey.value == expected }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Key restored")).fetchSemanticsNodes().isNotEmpty() }
        // Still onboarding: the restored key is used once an area is picked.
        compose.onNodeWithText("Restore from backup").assertIsDisplayed()
        compose.onNodeWithText("Dhanmondi").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(expected, container.identity.profile().pubkeyHex)
    }

    private class DocumentPlatform : PlatformActions {
        var saved: String? = null
        var savedName: String? = null
        var openText: String? = null
        override fun openUrl(url: String) = Unit
        override fun shareText(text: String) = Unit
        override fun copyToClipboard(label: String, text: String) = Unit
        override fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit) = onResult(null, null)
        override fun applyLanguage() = Unit
        override fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit) {
            savedName = suggestedName
            saved = text
            onResult(true)
        }
        override fun openTextDocument(onResult: (text: String?) -> Unit) = onResult(openText)
    }
}
