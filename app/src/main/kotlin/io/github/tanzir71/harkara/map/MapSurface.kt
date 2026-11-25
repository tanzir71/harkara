package io.github.tanzir71.harkara.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.map.LabelLanguage
import io.github.tanzir71.harkara.core.map.MapColors
import io.github.tanzir71.harkara.ui.theme.HarkaraColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Where the map looks. [bearing] is degrees clockwise (0 = north up). */
data class MapCamera(val center: LatLng, val zoom: Double, val bearing: Double = 0.0)

/** Lat/lng to pixels inside the map surface, for the Compose markers drawn on top. */
fun interface MapProjection {
    fun toScreen(position: LatLng): Offset?
}

/** Web Mercator for a camera and a surface size in pixels (256 px tiles scaled by [density]). */
class MercatorProjection(private val camera: MapCamera, private val widthPx: Float, private val heightPx: Float, private val density: Float) : MapProjection {
    private val scale = 256.0 * density * 2.0.pow(camera.zoom)

    private fun world(p: LatLng): Pair<Double, Double> {
        val x = (p.lng + 180.0) / 360.0 * scale
        val s = sin(Math.toRadians(p.lat.coerceIn(-85.0, 85.0)))
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * scale
        return x to y
    }

    override fun toScreen(position: LatLng): Offset {
        val (cx, cy) = world(camera.center)
        val (x, y) = world(position)
        val dx = x - cx
        val dy = y - cy
        val r = Math.toRadians(-camera.bearing)
        val rx = dx * cos(r) - dy * sin(r)
        val ry = dx * sin(r) + dy * cos(r)
        return Offset((widthPx / 2 + rx).toFloat(), (heightPx / 2 + ry).toFloat())
    }

    /** Inverse at the screen centre offset, used by the fake surface's drag. */
    fun shift(dxPx: Float, dyPx: Float): LatLng {
        val (cx, cy) = world(camera.center)
        val x = cx - dxPx
        val y = cy - dyPx
        val lng = x / scale * 360.0 - 180.0
        val n = PI - 2.0 * PI * y / scale
        val lat = Math.toDegrees(kotlin.math.atan(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n))))
        return LatLng(lat, lng)
    }
}

/** Camera state shared by the map surface and the screen (locate, compass reset). */
@Stable
class MapViewState(initial: MapCamera) {
    /** The camera the surface currently shows (the surface updates it as the user pans). */
    var camera by mutableStateOf(initial)
    var projection by mutableStateOf<MapProjection>(MapProjection { null })
    /** A camera the screen asks for; the surface animates there once per [requestId]. */
    var requested by mutableStateOf<MapCamera?>(null)
        private set
    var requestId by mutableIntStateOf(0)
        private set

    fun moveTo(center: LatLng, zoom: Double = camera.zoom, bearing: Double = camera.bearing) {
        requested = MapCamera(center, zoom, bearing)
        requestId++
    }

    fun northUp() = moveTo(camera.center, camera.zoom, 0.0)
}

/** What the surface renders: the installed pack in the theme's colours, labels in the app's language. */
data class MapStyleSpec(val packPath: String, val maxZoom: Int, val night: Boolean, val colors: MapColors, val labels: LabelLanguage)

/**
 * The map behind the Nearby Map screen. Only [MapLibreMapSurface] touches MapLibre (native, real phones);
 * tests use a fake that draws nothing native, so the screen's Compose tests run on the JVM.
 */
interface MapSurface {
    @Composable
    fun Render(style: MapStyleSpec, state: MapViewState, modifier: Modifier, onTap: () -> Unit)
}

/** The surface the app shows; null (tests, previews) means the Lite list is shown instead. */
val LocalMapSurface = staticCompositionLocalOf<MapSurface?> { null }

/** Map colours from the design tokens (map land/road/water/park/block, text, border). */
fun HarkaraColors.mapColors(): MapColors = MapColors(
    land = mapLand.hex(), road = mapRoad.hex(), water = mapWater.hex(), park = mapPark.hex(), block = mapBlock.hex(),
    label = textSecondary.hex(), labelHalo = mapLand.hex(), roadCasing = borderDefault.hex(),
)

private fun Color.hex(): String = String.format(java.util.Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)
