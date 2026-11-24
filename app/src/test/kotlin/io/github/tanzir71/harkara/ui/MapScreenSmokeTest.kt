package io.github.tanzir71.harkara.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapScreenSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `map screen renders under robolectric`() {
        compose.setContent { HarkaraTheme { MapScreen() } }

        compose.onNodeWithText("Peer map").assertIsDisplayed()
    }
}
