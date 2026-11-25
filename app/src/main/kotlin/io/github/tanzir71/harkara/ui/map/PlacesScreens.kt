package io.github.tanzir71.harkara.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.geo.Bearings
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.Heading
import io.github.tanzir71.harkara.core.location.CheckInStatus
import io.github.tanzir71.harkara.core.location.TripPlan
import io.github.tanzir71.harkara.core.map.MapPack
import io.github.tanzir71.harkara.core.map.MapStyle
import io.github.tanzir71.harkara.core.pins.PinFileFormat
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.map.InstalledPack
import io.github.tanzir71.harkara.map.PackState
import io.github.tanzir71.harkara.places.CheckInView
import io.github.tanzir71.harkara.places.PinView
import io.github.tanzir71.harkara.ui.components.CheckInCard
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.Tag
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.screens.BackBar
import io.github.tanzir71.harkara.ui.screens.CalmState
import io.github.tanzir71.harkara.ui.screens.hhmm
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import java.util.Locale

@Composable
fun visibilityLabel(v: PinVisibility): String = stringResource(
    when (v) {
        PinVisibility.PRIVATE -> R.string.pin_private
        PinVisibility.SHARED -> R.string.pin_shared
        PinVisibility.PUBLIC -> R.string.pin_public
    },
)

/** Me → Tools → My pins: saved pins (import/export) and check-ins. */
@Composable
fun MyPinsScreen(
    pins: List<PinView>,
    checkIns: List<CheckInView>,
    trip: TripPlan?,
    nameOf: (String) -> String,
    onBack: () -> Unit,
    onOpen: (PinView) -> Unit,
    onImport: () -> Unit,
    onExport: (PinFileFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.tool_pins), onBack)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Segmented(listOf(stringResource(R.string.pins_tab), stringResource(R.string.checkins_tab)), tab, { tab = it })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) {
                item {
                    HButton(stringResource(R.string.pins_import), onImport, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, height = 44.dp, icon = R.drawable.ic_plus)
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.pins_export), style = HarkaraTheme.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
                        PinFileFormat.entries.forEach { f -> Chip(f.extension.uppercase(Locale.ROOT), selected = false, onClick = { onExport(f) }) }
                    }
                }
                item { Text(stringResource(R.string.pins_export_note), style = HarkaraTheme.type.caption, color = c.textSecondary) }
                val mine = pins.filter { it.mine }
                if (mine.isEmpty()) {
                    item { CalmState(R.drawable.ic_pin, stringResource(R.string.pins_empty_title), stringResource(R.string.pins_empty_body)) }
                }
                items(mine, key = { it.pin.address }) { p -> PinRow(p) { onOpen(p) } }
            } else {
                if (trip != null) {
                    item {
                        HCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.trip_active, trip.destination, hhmm(trip.due)), style = HarkaraTheme.type.headline, color = c.textPrimary)
                                Text(stringResource(R.string.trip_watchers, trip.watchers.size), style = HarkaraTheme.type.caption, color = c.textSecondary)
                            }
                        }
                    }
                }
                if (checkIns.isEmpty()) {
                    item { CalmState(R.drawable.ic_check, stringResource(R.string.checkins_empty_title), stringResource(R.string.checkins_empty_body)) }
                }
                items(checkIns, key = { it.checkIn.id }) { v -> CheckInRow(v, nameOf) }
            }
        }
    }
}

@Composable
private fun PinRow(p: PinView, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    HCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(c.categoryColor(p.pin.color)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.pin.title, style = HarkaraTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1)
                if (p.pin.note.isNotBlank()) Text(p.pin.note, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 1)
            }
            Tag(visibilityLabel(p.pin.visibility))
        }
    }
}

/** A check-in, trip plan or overdue alert in the shared card style. */
@Composable
fun CheckInRow(v: CheckInView, nameOf: (String) -> String) {
    val ci = v.checkIn
    val who = if (v.own) stringResource(R.string.checkin_you) else ci.name ?: nameOf(ci.author)
    val title = when (ci.status) {
        CheckInStatus.SAFE -> stringResource(R.string.checkin_safe_title)
        CheckInStatus.TRIP -> stringResource(R.string.checkin_trip_title, ci.destination.orEmpty(), hhmm(ci.due ?: ci.createdAt))
        CheckInStatus.OVERDUE -> stringResource(R.string.checkin_overdue_title, ci.destination.orEmpty())
        CheckInStatus.CANCELLED -> stringResource(R.string.checkin_cancelled_title)
    }
    val detail = listOf(who, ci.place, hhmm(ci.createdAt)).filter { it.isNotBlank() }.joinToString(" · ")
    if (ci.status == CheckInStatus.OVERDUE) {
        val c = HarkaraTheme.colors
        HCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.stateSos), contentAlignment = Alignment.Center) {
                    HIcon(R.drawable.ic_sos, tint = c.textInverse, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = HarkaraTheme.type.headline, color = c.stateSos)
                    Text(detail, style = HarkaraTheme.type.caption, color = c.textSecondary)
                }
            }
        }
    } else {
        CheckInCard(title, if (ci.note.isNotBlank()) "$detail\n${ci.note}" else detail, Carrier.ONLINE, stringResource(R.string.via_relays))
    }
}

/** Me → Tools → Map packs (and Settings → Modules → Offline map pack). */
@Composable
fun MapPacksScreen(
    state: PackState,
    installed: InstalledPack?,
    recommended: MapPack?,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: (MapPack) -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar(stringResource(R.string.tool_map_packs), onBack)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HIcon(R.drawable.ic_map, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.packs_dhaka), style = HarkaraTheme.type.headline, color = c.textPrimary)
                    }
                    Text(
                        if (installed == null) stringResource(R.string.packs_none)
                        else stringResource(R.string.packs_installed, mb(installed.bytes), installed.maxZoom),
                        style = HarkaraTheme.type.caption, color = c.textSecondary,
                    )
                    if (installed != null && installed.sourceDate.isNotBlank()) {
                        Text(stringResource(R.string.packs_date, installed.sourceDate), style = HarkaraTheme.type.caption, color = c.textSecondary)
                    }
                }
            }
            when (state) {
                is PackState.Downloading -> {
                    Text(stringResource(R.string.packs_downloading, mb(state.done), mb(state.total)), style = HarkaraTheme.type.body, color = c.textPrimary)
                    val fraction = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.bgSunken)) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(c.bgInverse))
                    }
                }
                PackState.Checking -> Text(stringResource(R.string.packs_checking), style = HarkaraTheme.type.caption, color = c.textSecondary)
                is PackState.Available -> {
                    state.manifest.packs.sortedByDescending { it.maxzoom }.forEach { pack ->
                        val detailed = pack.maxzoom >= 15
                        HButton(
                            stringResource(if (detailed) R.string.packs_get_detailed else R.string.packs_get_light, mb(pack.bytes)),
                            { onDownload(pack) },
                            Modifier.fillMaxWidth(),
                            variant = if (pack == recommended) HButtonVariant.Primary else HButtonVariant.Secondary,
                        )
                    }
                }
                is PackState.Failed -> {
                    Text(stringResource(failure(state.reason)), style = HarkaraTheme.type.caption, color = c.stateSos)
                    HButton(stringResource(R.string.packs_check), onCheck, Modifier.fillMaxWidth())
                }
                is PackState.Idle -> HButton(stringResource(R.string.packs_check), onCheck, Modifier.fillMaxWidth(), icon = R.drawable.ic_map)
            }
            Text(stringResource(R.string.packs_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
            HButton(stringResource(R.string.packs_import), onImport, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
            if (installed != null) HButton(stringResource(R.string.packs_delete), onDelete, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
            Text(MapStyle.ATTRIBUTION, style = HarkaraTheme.type.label, color = c.textTertiary)
        }
    }
}

private fun failure(reason: PackState.Reason): Int = when (reason) {
    PackState.Reason.OFFLINE -> R.string.packs_fail_offline
    PackState.Reason.NO_RELEASE -> R.string.packs_fail_none
    PackState.Reason.CHECKSUM -> R.string.packs_fail_checksum
    PackState.Reason.NOT_A_MAP -> R.string.packs_fail_not_map
    PackState.Reason.NO_SPACE -> R.string.packs_fail_space
}

fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)

/** Something the compass can point at. */
data class CompassTarget(val id: String, val name: String, val position: io.github.tanzir71.harkara.core.geo.LatLng)

/**
 * Compass and the meet-up arrow: the dial turns with the phone; the arrow points at the chosen pin or
 * person. Without a heading sensor the arrow shows the bearing from north instead.
 */
@Composable
fun CompassScreen(
    heading: Double?,
    sensorAvailable: Boolean,
    here: io.github.tanzir71.harkara.core.geo.LatLng?,
    targets: List<CompassTarget>,
    selected: CompassTarget?,
    onSelect: (CompassTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    val toTarget = if (here != null && selected != null) Heading.between(here, selected.position) else null
    val dial = heading ?: 0.0
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar(stringResource(R.string.tool_compass), onBack)
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (heading != null) "${Bearings.degrees(heading)}° ${Bearings.cardinal(heading)}" else stringResource(R.string.compass_no_sensor),
                style = HarkaraTheme.type.display, color = c.textPrimary, textAlign = TextAlign.Center,
            )
            Box(
                Modifier.fillMaxWidth(0.8f).aspectRatio(1f).semantics { contentDescription = toTarget?.cardinal ?: "N" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(c.bgSurface, r)
                    drawCircle(c.borderDefault, r, style = Stroke(2.dp.toPx()))
                    rotate((-dial).toFloat()) {
                        for (i in 0 until 72) {
                            val long = i % 18 == 0
                            val a = Math.toRadians(i * 5.0)
                            val inner = r - (if (long) 18.dp else 8.dp).toPx()
                            val o = Offset(center.x + (r - 4.dp.toPx()) * kotlin.math.sin(a).toFloat(), center.y - (r - 4.dp.toPx()) * kotlin.math.cos(a).toFloat())
                            val n = Offset(center.x + inner * kotlin.math.sin(a).toFloat(), center.y - inner * kotlin.math.cos(a).toFloat())
                            drawLine(if (i == 0) c.stateSos else c.borderStrong, o, n, strokeWidth = (if (long) 3.dp else 1.5.dp).toPx())
                        }
                    }
                    if (toTarget != null) {
                        val turn = if (heading != null) Bearings.relative(toTarget.bearing, heading) else toTarget.bearing
                        rotate(turn.toFloat()) {
                            val tip = Offset(center.x, center.y - r * 0.72f)
                            val path = Path().apply {
                                moveTo(tip.x, tip.y)
                                lineTo(center.x + r * 0.16f, center.y + r * 0.18f)
                                lineTo(center.x, center.y + r * 0.06f)
                                lineTo(center.x - r * 0.16f, center.y + r * 0.18f)
                                close()
                            }
                            drawPath(path, c.accentFill)
                            drawPath(path, c.bgInverse, style = Stroke(2.dp.toPx()))
                        }
                    }
                    drawCircle(c.bgInverse, 6.dp.toPx())
                }
            }
            if (toTarget != null && selected != null) {
                Text(selected.name, style = HarkaraTheme.type.title, color = c.textPrimary)
                Text(
                    stringResource(R.string.compass_to_target, GeoMath.distanceLabel(toTarget.distanceMeters), toTarget.cardinal, Bearings.degrees(toTarget.bearing)),
                    style = HarkaraTheme.type.body, color = c.textSecondary,
                )
            } else if (here == null) {
                Text(stringResource(R.string.compass_no_fix), style = HarkaraTheme.type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            }
            if (!sensorAvailable) Text(stringResource(R.string.compass_sensor_note), style = HarkaraTheme.type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
            if (targets.isNotEmpty()) {
                Text(stringResource(R.string.compass_point_to), style = HarkaraTheme.type.label, color = c.textSecondary, modifier = Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t -> Chip(t.name, selected = t.id == selected?.id, onClick = { onSelect(t) }) }
                        }
                    }
                }
            }
            Text(stringResource(R.string.compass_battery), style = HarkaraTheme.type.caption, color = c.textTertiary, textAlign = TextAlign.Center)
        }
    }
}
