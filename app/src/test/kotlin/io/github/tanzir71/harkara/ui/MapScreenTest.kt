package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.tanzir71.harkara.core.map.LabelLanguage
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.map.mapColors
import io.github.tanzir71.harkara.ui.components.PinCard
import io.github.tanzir71.harkara.ui.map.FakeMapSurface
import io.github.tanzir71.harkara.ui.map.MapActionsCard
import io.github.tanzir71.harkara.ui.map.MapSamples
import io.github.tanzir71.harkara.ui.map.NearbyMapContent
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screen 02 on the JVM: the map surface is a fake, so no native rendering is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class MapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(surface: FakeMapSurface?, onCompass: () -> Unit = {}, onLocate: () -> Unit = {}) {
        compose.setContent {
            HarkaraTheme(night = false) {
                var enabled by remember { mutableStateOf(MapLayer.DEFAULT) }
                var selected by remember { mutableStateOf<MapMarker?>(null) }
                val state = remember { MapViewState(MapSamples.camera) }
                NearbyScreen(
                    "Dhanmondi 27", true, SampleData.meshOnly, SampleData.posts,
                    mapContent = { m, modeSwitch ->
                        NearbyMapContent(
                            modeSwitch = modeSwitch,
                            surface = surface,
                            style = MapStyleSpec("/maps/dhaka-z15.pmtiles", 15, false, HarkaraTheme.colors.mapColors(), LabelLanguage.BANGLA),
                            mapState = state,
                            markers = MapSampleData.markers.filter { it.layer in enabled },
                            enabled = enabled,
                            onToggleLayer = { l -> enabled = if (l in enabled) enabled - l else enabled + l },
                            user = MapSampleData.user,
                            origin = MapSampleData.user,
                            selectedId = selected?.id,
                            onSelect = { selected = it },
                            personTag = { "Rafi · 38 min left" },
                            bottomCard = {
                                val s = selected
                                if (s != null && s.id == "pin:water") PinCard(SampleData.pin, Modifier.fillMaxWidth())
                                else if (s != null) Text("selected ${s.id}")
                                else MapActionsCard({}, {}, {}, {})
                            },
                            onCompass = onCompass,
                            onLocate = onLocate,
                            modifier = m,
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Map").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `map mode renders the fake surface with markers, chips, buttons and attribution`() {
        val surface = FakeMapSurface()
        var compass = 0
        var locate = 0
        show(surface, onCompass = { compass++ }, onLocate = { locate++ })

        compose.onNodeWithTag("map-surface").assertIsDisplayed()
        assertTrue(surface.renders > 0)
        assertEquals(LabelLanguage.BANGLA, surface.lastStyle!!.labels)
        compose.onNodeWithText("© OpenStreetMap contributors · Protomaps").assertIsDisplayed()
        compose.onNodeWithText("Posts").assertIsDisplayed()
        compose.onNodeWithContentDescription("Water point").assertIsDisplayed()
        compose.onNodeWithContentDescription("Medicine pickup, ৳120").assertIsDisplayed()
        compose.onNodeWithText("Rafi · 38 min left").assertIsDisplayed()
        compose.onNodeWithContentDescription("You are here").assertIsDisplayed()
        compose.onNodeWithText("Drop pin").assertIsDisplayed()

        compose.onNodeWithContentDescription("Compass, north up").performClick()
        compose.onNodeWithContentDescription("Show where I am").performClick()
        assertEquals(1, compass)
        assertEquals(1, locate)
    }

    @Test
    fun `tapping a pin shows its card with still there, chips hide layers`() {
        show(FakeMapSurface())
        compose.onNodeWithContentDescription("Water point").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Still there?").assertIsDisplayed()
        compose.onNodeWithText("Tubewell behind the lake mosque, open 7:00 to 10:00").assertIsDisplayed()

        compose.onAllNodesWithContentDescription("Medicine pickup, ৳120").assertCountEquals(1)
        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Medicine pickup, ৳120").assertCountEquals(0)
        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Medicine pickup, ৳120").assertCountEquals(1)
    }

    @Test
    fun `without a surface (no pack, Lite) the map is a list with distances`() {
        show(null)
        compose.onAllNodesWithContentDescription("Water point").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("You are here").assertCountEquals(0)
        compose.onNodeWithText("Gridlocked from Science Lab to Kalabagan").assertIsDisplayed()
        compose.onNodeWithText("Rafi").assertIsDisplayed()
        compose.onNodeWithText("Feed").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SampleData.posts.first().text).assertIsDisplayed()
    }
}
