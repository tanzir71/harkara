package io.github.tanzir71.harkara.ui.map

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.Heading
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.core.map.MapStyle
import io.github.tanzir71.harkara.core.map.MarkerKind
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapSurface
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HIconButton
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

@StringRes
fun MapLayer.label(): Int = when (this) {
    MapLayer.POSTS -> R.string.map_layer_posts
    MapLayer.MY_PINS -> R.string.map_layer_my_pins
    MapLayer.PEOPLE -> R.string.map_layer_people
    MapLayer.TASKS -> R.string.map_layer_tasks
    MapLayer.NODES -> R.string.map_layer_nodes
    MapLayer.PLACES -> R.string.map_layer_places
}

/** Layer chips: on = ink, off = surface. */
@Composable
fun LayerChips(enabled: Set<MapLayer>, onToggle: (MapLayer) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapLayer.entries.forEach { layer -> Chip(stringResource(layer.label()), selected = layer in enabled, onClick = { onToggle(layer) }) }
    }
}

/**
 * Screen 02, Map mode: the map surface (or the no-map Lite list), markers as Compose overlays, layer
 * chips, compass and locate buttons, the OpenStreetMap attribution and a card for what's selected.
 */
@Composable
fun NearbyMapContent(
    modeSwitch: @Composable () -> Unit,
    surface: MapSurface?,
    style: MapStyleSpec?,
    mapState: MapViewState,
    markers: List<MapMarker>,
    enabled: Set<MapLayer>,
    onToggleLayer: (MapLayer) -> Unit,
    user: LatLng?,
    origin: LatLng,
    selectedId: String?,
    onSelect: (MapMarker?) -> Unit,
    personTag: (MapMarker) -> String,
    bottomCard: @Composable () -> Unit,
    onCompass: () -> Unit,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
    liteBanner: (@Composable () -> Unit)? = null,
) {
    if (surface == null || style == null) {
        LiteMapList(modeSwitch, markers, enabled, onToggleLayer, origin, onSelect, bottomCard, modifier, liteBanner)
        return
    }
    val c = HarkaraTheme.colors
    Box(modifier.fillMaxSize().background(c.mapLand)) {
        surface.Render(style, mapState, Modifier.fillMaxSize().semantics { testTag = "map-surface" }) { onSelect(null) }
        MapOverlay(
            markerItems(markers, selectedId, user, stringResource(R.string.map_you_are_here), personTag) { onSelect(it) },
            mapState.projection,
            Modifier.fillMaxSize(),
        )
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            modeSwitch()
            LayerChips(enabled, onToggleLayer)
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HIconButton(R.drawable.ic_compass, stringResource(R.string.map_compass), onCompass)
            HIconButton(R.drawable.ic_locate, stringResource(R.string.map_locate), onLocate)
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Text(
                MapStyle.ATTRIBUTION,
                style = HarkaraTheme.type.label,
                color = c.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            bottomCard()
        }
    }
}

/** No-map Lite view: what the map would show, as a list with distance and a bearing arrow. */
@Composable
fun LiteMapList(
    modeSwitch: @Composable () -> Unit,
    markers: List<MapMarker>,
    enabled: Set<MapLayer>,
    onToggleLayer: (MapLayer) -> Unit,
    origin: LatLng,
    onSelect: (MapMarker?) -> Unit,
    bottomCard: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
) {
    val sorted = markers.map { it to Heading.between(origin, it.position) }.sortedBy { it.second.distanceMeters }
    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 180.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { modeSwitch() }
            item { LayerChips(enabled, onToggleLayer) }
            if (banner != null) item { Box(Modifier.padding(horizontal = 16.dp)) { banner() } }
            if (sorted.isEmpty()) {
                item {
                    io.github.tanzir71.harkara.ui.screens.CalmState(R.drawable.ic_map, stringResource(R.string.map_lite_empty_title), stringResource(R.string.map_lite_empty_body))
                }
            } else {
                items(sorted, key = { it.first.id }) { (m, h) -> LiteRow(m, h) { onSelect(m) } }
            }
        }
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) { bottomCard() }
    }
}

@DrawableRes
private fun MapMarker.icon(): Int = when (kind) {
    MarkerKind.POST -> R.drawable.ic_nearby
    MarkerKind.PIN -> R.drawable.ic_pin
    MarkerKind.PERSON -> R.drawable.ic_locate
    MarkerKind.TASK -> R.drawable.ic_tasks
    MarkerKind.NODE -> R.drawable.ic_node
}

@Composable
private fun LiteRow(m: MapMarker, h: Heading, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    val title = (if (m.kind == MarkerKind.PIN) m.label else m.detail) ?: m.label ?: stringResource(m.layer.label())
    val distance = GeoMath.distanceLabel(h.distanceMeters)
    HCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.kind == MarkerKind.PERSON) {
                Avatar(m.label.orEmpty(), size = 32.dp, live = true)
            } else {
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.markerColor(m.color)), contentAlignment = Alignment.Center) {
                    HIcon(m.icon(), tint = c.textInverse, size = 16.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = HarkaraTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1)
                val sub = listOfNotNull(stringResource(m.layer.label()), m.label?.takeIf { m.kind == MarkerKind.TASK || m.kind == MarkerKind.POST }).joinToString(" · ")
                Text(sub, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                HIcon(
                    R.drawable.ic_bearing,
                    Modifier.rotate((h.bearing - 45.0).toFloat()).semantics { contentDescription = h.cardinal },
                    tint = c.textPrimary,
                    size = 20.dp,
                )
                Text(distance, style = HarkaraTheme.type.label, color = c.textSecondary)
            }
        }
    }
}

/** When nothing is selected: the map's own actions in one card (a module that is off has no tile). */
@Composable
fun MapActionsCard(
    onDropPin: (() -> Unit)?,
    onShareLocation: (() -> Unit)?,
    onCheckIn: (() -> Unit)?,
    onLiveShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
    liveRunning: String? = null,
    onStopLive: () -> Unit = {},
) {
    val c = HarkaraTheme.colors
    HCard(modifier.fillMaxWidth(), padding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)) {
        Column {
            if (liveRunning != null) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onStopLive).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.stateOnline))
                    Spacer(Modifier.width(8.dp))
                    Text(liveRunning, style = HarkaraTheme.type.caption, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.live_stop), style = HarkaraTheme.type.label, color = c.stateSos)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                onDropPin?.let { ActionTile(R.drawable.ic_pin, stringResource(R.string.map_action_pin), it, Modifier.weight(1f)) }
                onShareLocation?.let { ActionTile(R.drawable.ic_send, stringResource(R.string.map_action_share), it, Modifier.weight(1f)) }
                onCheckIn?.let { ActionTile(R.drawable.ic_check, stringResource(R.string.map_action_check_in), it, Modifier.weight(1f)) }
                onLiveShare?.let { ActionTile(R.drawable.ic_locate, stringResource(R.string.map_action_live), it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionTile(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    val c = HarkaraTheme.colors
    Column(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(io.github.tanzir71.harkara.ui.theme.Tokens.Radius.md)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HIcon(icon, size = 22.dp)
        Text(label, style = HarkaraTheme.type.label, color = c.textSecondary, maxLines = 1)
    }
}
