package io.github.tanzir71.harkara.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tanzir71.harkara.core.map.MapStyle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import io.github.tanzir71.harkara.core.geo.LatLng as GeoLatLng
import org.maplibre.android.geometry.LatLng as MlLatLng

/**
 * The real map: MapLibre Native drawing the installed PMTiles pack (`pmtiles://file://…`) with the style
 * built from the design tokens and glyphs bundled in the APK. This file is the only code that touches
 * MapLibre; it never runs in JVM tests. The MapView follows the screen's lifecycle, so nothing renders
 * (and no GPU work happens) while the screen is hidden.
 */
object MapLibreMapSurface : MapSurface {
    @Volatile private var initialised = false

    private fun init(context: Context) {
        if (!initialised) {
            MapLibre.getInstance(context.applicationContext)
            initialised = true
        }
    }

    @Composable
    override fun Render(style: MapStyleSpec, state: MapViewState, modifier: Modifier, onTap: () -> Unit) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val tap by rememberUpdatedState(onTap)
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        val view = remember {
            init(context)
            val options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(false)
                .logoEnabled(false)
                .compassEnabled(false)
            MapView(context, options).apply { onCreate(null) }
        }

        DisposableEffect(lifecycle, view) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                view.onPause()
                view.onStop()
                view.onDestroy()
            }
        }

        DisposableEffect(view) {
            view.getMapAsync { m ->
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                val bbox = MapStyle.DHAKA_BBOX
                m.setLatLngBoundsForCameraTarget(LatLngBounds.from(bbox[3], bbox[2], bbox[1], bbox[0]))
                m.setMinZoomPreference(10.0)
                m.setMaxZoomPreference(18.0)
                val c = state.camera
                m.moveCamera(CameraUpdateFactory.newCameraPosition(position(c)))
                val publish = {
                    val pos = m.cameraPosition
                    val target = pos.target
                    if (target != null) state.camera = MapCamera(GeoLatLng(target.latitude, target.longitude), pos.zoom, pos.bearing)
                    val projection = m.projection
                    state.projection = MapProjection { p -> projection.toScreenLocation(MlLatLng(p.lat, p.lng)).let { Offset(it.x, it.y) } }
                }
                m.addOnCameraMoveListener { publish() }
                m.addOnCameraIdleListener { publish() }
                m.addOnMapClickListener { tap(); false }
                publish()
                map = m
            }
            onDispose { map = null }
        }

        val current = map
        LaunchedEffect(current, style) {
            current ?: return@LaunchedEffect
            val json = MapStyle.build(
                style.colors,
                night = style.night,
                labels = style.labels,
                maxZoom = style.maxZoom,
                sourceUrl = MapStyle.pmtilesUrl(style.packPath),
            ).toString()
            current.setStyle(Style.Builder().fromJson(json))
        }
        LaunchedEffect(current, state.requestId) {
            val m = current ?: return@LaunchedEffect
            val r = state.requested ?: return@LaunchedEffect
            m.animateCamera(CameraUpdateFactory.newCameraPosition(position(r)), 400)
        }

        AndroidView(factory = { view }, modifier = modifier)
    }

    private fun position(c: MapCamera): CameraPosition =
        CameraPosition.Builder().target(MlLatLng(c.center.lat, c.center.lng)).zoom(c.zoom).bearing(c.bearing).tilt(0.0).build()
}
