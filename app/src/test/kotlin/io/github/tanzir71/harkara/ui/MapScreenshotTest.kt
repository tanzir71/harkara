package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.location.CheckIn
import io.github.tanzir71.harkara.core.location.CheckInStatus
import io.github.tanzir71.harkara.core.location.LocationCardData
import io.github.tanzir71.harkara.core.map.LabelLanguage
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.core.map.MapPack
import io.github.tanzir71.harkara.core.map.MapsManifest
import io.github.tanzir71.harkara.core.map.MapsRelease
import io.github.tanzir71.harkara.core.map.MarkerKind
import io.github.tanzir71.harkara.core.pins.Pin
import io.github.tanzir71.harkara.core.pins.PinTally
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.map.InstalledPack
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.map.PackState
import io.github.tanzir71.harkara.map.mapColors
import io.github.tanzir71.harkara.places.CheckInView
import io.github.tanzir71.harkara.places.PinView
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.PinCard
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.map.CheckInSheetContent
import io.github.tanzir71.harkara.ui.map.CompassScreen
import io.github.tanzir71.harkara.ui.map.CompassTarget
import io.github.tanzir71.harkara.ui.map.FakeMapSurface
import io.github.tanzir71.harkara.ui.map.LocationCardSheetContent
import io.github.tanzir71.harkara.ui.map.MapActionsCard
import io.github.tanzir71.harkara.ui.map.MapPacksScreen
import io.github.tanzir71.harkara.ui.map.MapSamples
import io.github.tanzir71.harkara.ui.map.MyPinsScreen
import io.github.tanzir71.harkara.ui.map.NearbyMapContent
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screen 02 (Nearby map) with the reference's content, its Lite view, and the M9 pages and sheets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class MapScreenshotTest {
    @Composable
    private fun WithTabs(content: @Composable (Modifier) -> Unit) {
        Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f))
            TabBar(HarkaraTab.Nearby, {})
        }
    }

    @Composable
    private fun MapScreen(surface: FakeMapSurface?, selected: String?, banner: (@Composable () -> Unit)? = null, bottom: @Composable () -> Unit) = WithTabs { modifier ->
        NearbyScreen(
            "Dhanmondi 27", areaAuto = true, connectivity = SampleData.meshOnly, posts = SampleData.posts, modifier = modifier, initialMode = 1,
            mapContent = { m, modeSwitch ->
                NearbyMapContent(
                    modeSwitch = modeSwitch,
                    surface = surface,
                    style = MapStyleSpec("/maps/dhaka-z15.pmtiles", 15, HarkaraTheme.isNight, HarkaraTheme.colors.mapColors(), LabelLanguage.ENGLISH),
                    mapState = remember { MapViewState(MapSamples.camera) },
                    markers = MapSampleData.markers,
                    enabled = MapLayer.DEFAULT,
                    onToggleLayer = {},
                    user = MapSampleData.user,
                    origin = MapSampleData.user,
                    selectedId = selected,
                    onSelect = {},
                    personTag = { "Rafi · 38 min left" },
                    bottomCard = bottom,
                    onCompass = {},
                    onLocate = {},
                    modifier = m,
                    liteBanner = banner,
                )
            },
        )
    }

    @Test
    fun nearbyMap() = captureDayNight("screen-02-nearby-map", padded = false) {
        MapScreen(FakeMapSurface(), "pin:water") { PinCard(SampleData.pin, Modifier.fillMaxWidth()) }
    }

    @Test
    fun nearbyMapActions() = captureDayNight("screen-02b-nearby-map-actions", padded = false) {
        MapScreen(FakeMapSurface(), null) { MapActionsCard({}, {}, {}, {}, liveRunning = "Sharing with 2 until 21:40") }
    }

    @Test
    fun nearbyLite() = captureDayNight("screen-02c-nearby-lite", padded = false) {
        MapScreen(null, null, banner = { io.github.tanzir71.harkara.ui.map.GetMapCard {} }) { }
    }

    @Test
    fun mapPacks() = captureDayNight("screen-map-packs", padded = false) {
        val manifest = MapsManifest(
            sourceDate = "20261124",
            packs = listOf(
                MapPack("dhaka-z15.pmtiles", 15, 22_789_276, "8de855863e31e502917656701da314314e2cb92f881ef9a3ea1c6b19c0d94e5d"),
                MapPack("dhaka-z13.pmtiles", 13, 2_804_192, "882fcc9a7a464b3f7d9bca13f28a6aed16dda7c6abc3b4a9a454ad5476a1a07a"),
            ),
        )
        MapPacksScreen(
            state = PackState.Available(null, MapsRelease("maps-202611", emptyMap()), manifest),
            installed = InstalledPack("dhaka-z13.pmtiles", 13, 2_804_192, "882f", "20261124", "/x"),
            recommended = manifest.packs.first(),
            onBack = {}, onCheck = {}, onDownload = {}, onImport = {}, onDelete = {},
        )
    }

    @Test
    fun myPinsAndCheckIns() = captureDayNight("screen-my-pins", padded = false) {
        MyPinsScreen(
            pins = MapSampleData.pinViews,
            checkIns = emptyList(), trip = null, nameOf = { it.take(8) },
            onBack = {}, onOpen = {}, onImport = {}, onExport = {},
        )
    }

    @Test
    fun compass() = captureDayNight("screen-compass", padded = false) {
        val target = CompassTarget("pin:water", "Water point", MapSamples.at(203f, 250f))
        CompassScreen(heading = 20.0, sensorAvailable = true, here = MapSampleData.user, targets = listOf(target, CompassTarget("person:rafi", "Rafi", MapSamples.at(57f, 409f))), selected = target, onSelect = {}, onBack = {})
    }

    @Test
    fun locationCardSheet() = captureDayNight("screen-location-card", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                LocationCardSheetContent(
                    cardFor = { p -> LocationCardData.make(LatLng(23.74651, 90.37602), p, "Dhanmondi 27") },
                    onShare = {}, onCopy = {}, modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }

    @Test
    fun checkInSheet() = captureDayNight("screen-check-in", padded = false) {
        Box(Modifier.fillMaxSize().background(HarkaraTheme.colors.bgCanvas), contentAlignment = Alignment.BottomCenter) {
            Column {
                io.github.tanzir71.harkara.ui.map.CheckInRow(MapSampleData.overdue) { "Nabila" }
                SheetSurface {
                    CheckInSheetContent(
                        defaultPlace = "Home", trip = null, tripsEnabled = true,
                        onCheckIn = { _, _, _ -> }, onStartTrip = { _, _, _ -> }, onCancelTrip = {},
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** The reference screen's content, placed at the reference's positions. */
object MapSampleData {
    private fun p(x: Float, y: Float) = MapSamples.at(x, y)

    val user: LatLng = p(152f, 436f)

    val markers = listOf(
        MapMarker("post:food", MapLayer.POSTS, MarkerKind.POST, p(112f, 163f), "category/food", "Food", "Khichuri at the Road 27 stall till 3 pm"),
        MapMarker("post:traffic1", MapLayer.POSTS, MarkerKind.POST, p(107f, 372f), "category/traffic", "Traffic", "Gridlocked from Science Lab to Kalabagan"),
        MapMarker("post:traffic2", MapLayer.POSTS, MarkerKind.POST, p(163f, 375f), "category/traffic", "Traffic", "Mirpur Rd crawling at the Road 6 signal"),
        MapMarker("post:alert", MapLayer.POSTS, MarkerKind.POST, p(227f, 396f), "category/alert", "Alert", "Waterlogging near Rapa Plaza, ankle deep"),
        MapMarker("post:chatter", MapLayer.POSTS, MarkerKind.POST, p(313f, 483f), "category/chatter", "Chatter", "Power back on in Road 8"),
        MapMarker("pin:stock", MapLayer.MY_PINS, MarkerKind.PIN, p(275f, 209f), "category/stock", "Fuel pump"),
        MapMarker("pin:water", MapLayer.PLACES, MarkerKind.PIN, p(203f, 250f), "category/stock", "Water point"),
        MapMarker("task:t1", MapLayer.TASKS, MarkerKind.TASK, p(305f, 411f), "ink", "৳120", "Medicine pickup"),
        MapMarker("node:n1", MapLayer.NODES, MarkerKind.NODE, p(36f, 134f), "state/node", null, "Road 27 node"),
        MapMarker("person:rafi", MapLayer.PEOPLE, MarkerKind.PERSON, p(57f, 409f), "state/online", "RA", "Rafi"),
    )

    val pinViews = listOf(
        PinView(Pin("w", "me", PinVisibility.PUBLIC, "Water point", "Tubewell behind the lake mosque, open 7:00 to 10:00", MapSamples.at(203f, 250f), Category.STOCK, 1), PinTally(4, 1), true),
        PinView(Pin("h", "me", PinVisibility.PRIVATE, "Home gate", "Blue gate, ring twice", MapSamples.at(80f, 200f), Category.FOOD, 1), PinTally(0, 0), true),
        PinView(Pin("s", "me", PinVisibility.SHARED, "Flood shelter", "Govt. school, 2nd floor", MapSamples.at(250f, 500f), Category.ALERT, 1), PinTally(0, 0), true),
    )

    val overdue = CheckInView(
        CheckIn("c1", "nabila", CheckInStatus.OVERDUE, "Mohakhali", null, "t1", "Banani 11", 1_764_000_000, "", 1_764_000_000, emptyList()),
        own = false,
    )
}
