package io.github.tanzir71.harkara.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.map.MapCamera
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapSurface
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.map.MercatorProjection
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/**
 * Test stand-in for the MapLibre surface: no native rendering. It projects with plain Web Mercator so the
 * Compose markers land where they would, and draws a schematic Dhanmondi block from the map tokens so
 * screenshots show the screen's layout. It records what the screen asked of it.
 */
class FakeMapSurface : MapSurface {
    var renders = 0
    var lastStyle: MapStyleSpec? = null
    val cameraRequests = mutableListOf<MapCamera>()

    @Composable
    override fun Render(style: MapStyleSpec, state: MapViewState, modifier: Modifier, onTap: () -> Unit) {
        renders++
        lastStyle = style
        state.requested?.let { if (cameraRequests.lastOrNull() != it) cameraRequests += it }
        val density = LocalDensity.current.density
        val c = HarkaraTheme.colors
        Box(
            modifier
                .background(c.mapLand)
                .onSizeChanged { size -> state.projection = MercatorProjection(state.camera, size.width.toFloat(), size.height.toFloat(), density) }
                .pointerInput(Unit) { detectTapGestures { onTap() } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val road = 14.dp.toPx()
                val r = CornerRadius(4.dp.toPx())
                val cols = listOf(0f, 0.34f, 0.72f, 1f)
                val rows = listOf(0f, 0.13f, 0.58f, 0.74f, 1f)
                for (i in 0 until cols.size - 1) for (j in 0 until rows.size - 1) {
                    val x0 = w * cols[i] + road / 2
                    val x1 = w * cols[i + 1] - road / 2
                    val y0 = h * rows[j] + road / 2
                    val y1 = h * rows[j + 1] - road / 2
                    val bw = (x1 - x0 - 12.dp.toPx()) / 2
                    val bh = (y1 - y0 - 12.dp.toPx()) / 2
                    for (a in 0..1) for (b in 0..1) {
                        drawRoundRect(c.mapBlock, Offset(x0 + 4.dp.toPx() + a * (bw + 4.dp.toPx()), y0 + 4.dp.toPx() + b * (bh + 4.dp.toPx())), Size(bw, bh), r)
                    }
                }
                drawRoundRect(c.mapPark, Offset(8.dp.toPx(), h * 0.42f), Size(w * 0.28f, h * 0.12f), CornerRadius(10.dp.toPx()))
                val lake = Path().apply {
                    moveTo(w * 0.45f, h * 0.30f)
                    cubicTo(w * 0.45f, h * 0.18f, w * 0.64f, h * 0.18f, w * 0.64f, h * 0.30f)
                    lineTo(w * 0.64f, h * 0.44f)
                    cubicTo(w * 0.80f, h * 0.44f, w * 0.95f, h * 0.45f, w * 0.95f, h * 0.52f)
                    cubicTo(w * 0.95f, h * 0.58f, w * 0.70f, h * 0.57f, w * 0.60f, h * 0.55f)
                    cubicTo(w * 0.50f, h * 0.55f, w * 0.45f, h * 0.50f, w * 0.45f, h * 0.44f)
                    close()
                }
                drawPath(lake, c.mapWater)
                cols.drop(1).dropLast(1).forEach { f -> drawRect(c.mapRoad, Offset(w * f - road / 2, 0f), Size(road, h)) }
                rows.drop(1).dropLast(1).forEach { f -> drawRect(c.mapRoad, Offset(0f, h * f - road / 2), Size(w, road)) }
            }
            val label = HarkaraTheme.type.label
            Text("Road 27", style = label, color = c.textSecondary, modifier = Modifier.offset(270.dp, 82.dp))
            Text("Dhanmondi Lake", style = label, color = c.stateNode, modifier = Modifier.offset(168.dp, 176.dp))
            Text("Satmasjid Rd", style = label, color = c.textSecondary, modifier = Modifier.offset(264.dp, 230.dp))
            Text("Mirpur Rd", style = label, color = c.textSecondary, modifier = Modifier.offset(14.dp, 366.dp))
            Column(Modifier.offset(22.dp, 292.dp)) {
                Text("DHANMONDI", style = HarkaraTheme.type.micro, color = c.textSecondary)
                Text("ধানমন্ডি", style = HarkaraTheme.type.banglaLabel, color = c.textSecondary)
            }
        }
    }
}

/** Reference-screen positions: lat/lng for a point (in dp) of a 360 × 650 dp map at zoom 16 around Dhanmondi 27. */
object MapSamples {
    val center = LatLng(23.7465, 90.3760)
    val camera = MapCamera(center, 16.0)
    private val ref = MercatorProjection(camera, 360f, 650f, 1f)

    fun at(x: Float, y: Float): LatLng = ref.shift(-(x - 180f), -(y - 325f))
}
