package io.github.tanzir71.harkara.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.tanzir71.harkara.ui.onboarding.OnboardingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingSettingsSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `onboarding asks for display name`() {
        compose.setContent { HarkaraTheme { OnboardingScreen() } }

        compose.onNodeWithText("Display name").assertIsDisplayed()
    }

    @Test
    fun `settings screen renders`() {
        compose.setContent { HarkaraTheme { SettingsScreen() } }

        compose.onNodeWithText("Settings").assertIsDisplayed()
    }
}
