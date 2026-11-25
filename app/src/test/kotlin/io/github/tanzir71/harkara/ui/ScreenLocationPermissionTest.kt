package io.github.tanzir71.harkara.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.location.LocationCardData
import io.github.tanzir71.harkara.core.location.LocationPrecision
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.core.map.MarkerKind
import io.github.tanzir71.harkara.map.MapCamera
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.places.Fix
import io.github.tanzir71.harkara.places.LocationSource
import io.github.tanzir71.harkara.ui.map.NearbyMapContent
import io.github.tanzir71.harkara.ui.map.rememberScreenLocation
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fake location and permission results only: no native map, GPS, hardware sensors or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-mdpi")
class ScreenLocationPermissionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val area = LatLng(23.70, 90.30)
    private val spot = LatLng(23.75, 90.38)
    private val enabled = mutableStateOf(true)
    private val open = mutableStateOf(true)
    private val source = Source()
    private val permissions = Permissions()
    private var user: LatLng? = null
    private var card: LocationCardData? = null
    private var compassClicks = 0

    private class Source : LocationSource {
        var cached: Fix? = null
        var active = 0
        val values = MutableSharedFlow<Fix>(extraBufferCapacity = 4)
        override fun lastKnown() = cached
        override fun updates(intervalMillis: Long): Flow<Fix> = flow {
            active++
            try { emitAll(values) } finally { active-- }
        }
    }

    private class Permissions : ActivityResultRegistryOwner {
        var requests = 0
        var code = 0
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requests++
                code = requestCode
            }
        }
        fun complete(granted: Boolean) {
            activityResultRegistry.dispatchResult(code, mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to false,
                Manifest.permission.ACCESS_COARSE_LOCATION to granted,
            ))
        }
    }

    @Before
    fun denyInitially() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                HarkaraTheme(night = false) {
                    val isOpen by open
                    if (isOpen) {
                        val active by enabled
                        val location = rememberScreenLocation(source, active, 10_000)
                        user = location.fix?.position
                        card = user?.let { LocationCardData.make(it, LocationPrecision.EXACT, "Dhaka") }
                        NearbyMapContent(
                            modeSwitch = {}, surface = null, style = null,
                            mapState = remember { MapViewState(MapCamera(area, 15.0)) },
                            markers = listOf(MapMarker("point", MapLayer.PLACES, MarkerKind.PIN, spot, "category/stock", "Point", null)),
                            enabled = MapLayer.DEFAULT, onToggleLayer = {}, user = user, origin = user ?: area,
                            selectedId = null, onSelect = {}, personTag = { "" }, bottomCard = {},
                            onCompass = { compassClicks++ }, onLocate = if (active) location.locate else null,
                            liteBanner = { Text(stringResource(R.string.map_get_title)) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun noPackLiteExposesExplicitLocateAndGrantUpdatesUserDistanceAndCard() {
        show()
        compose.onNodeWithContentDescription("Show where I am").assertIsDisplayed()
        assertEquals(0, permissions.requests)
        assertEquals(0, source.active)
        assertNull(user)
        compose.onNodeWithContentDescription("Compass, north up").performClick()
        assertEquals(1, compassClicks)
        compose.onNodeWithContentDescription("Show where I am").performClick()
        assertEquals(1, permissions.requests)
        val fix = Fix(spot, 100, 1)
        compose.runOnIdle {
            source.cached = fix
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.complete(true)
        }
        compose.waitForIdle()
        assertEquals(1, source.active)
        assertEquals(spot, user)
        assertEquals(spot, card?.position)
        compose.onNodeWithText(GeoMath.distanceLabel(0.0)).assertIsDisplayed()
        val newer = Fix(LatLng(23.76, 90.39), 30, 2)
        compose.runOnIdle { source.values.tryEmit(newer) }
        compose.waitForIdle()
        assertEquals(newer.position, user)
        assertEquals(newer.position, card?.position)
    }

    @Test
    fun denialDoesNotStartGpsOrPromptAgainAutomatically() {
        show()
        compose.onNodeWithContentDescription("Show where I am").performClick()
        compose.runOnIdle { permissions.complete(false) }
        compose.waitForIdle()
        assertEquals(1, permissions.requests)
        assertEquals(0, source.active)
        assertNull(user)
    }

    @Test
    fun moduleOffAndScreenDisposalStopCollection() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        show()
        compose.waitForIdle()
        assertEquals(1, source.active)
        assertEquals(0, permissions.requests)
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        assertEquals(0, source.active)
        assertNull(user)
        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        assertEquals(1, source.active)
        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        assertEquals(0, source.active)
    }

    @Test
    fun initiallyDisabledModuleNeitherCollectsNorOffersPermissionPrompt() {
        enabled.value = false
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        show()
        compose.onNodeWithContentDescription("Show where I am").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(0, permissions.requests)
        assertEquals(0, source.active)
        assertNull(user)
    }
}
