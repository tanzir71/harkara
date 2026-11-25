package io.github.tanzir71.harkara.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.core.map.MarkerKind
import io.github.tanzir71.harkara.map.MapProjection
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.theme.HarkaraColors
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** The design token a marker's [MapMarker.color] names. */
fun HarkaraColors.markerColor(token: String): Color = when {
    token.startsWith("category/") -> Category.fromTag(token.removePrefix("category/"))?.let { categoryColor(it) } ?: catChatter
    token == "state/online" -> stateOnline
    token == "state/node" -> stateNode
    else -> bgInverse
}

/** One thing drawn over the map at [position]; [anchorX]/[anchorY] say which point of it sits there. */
class OverlayItem(
    val key: String,
    val position: LatLng,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val offsetX: Dp = 0.dp,
    val offsetY: Dp = 0.dp,
    val content: @Composable () -> Unit,
)

/** Places [items] at their projected points; items off the surface are skipped. */
@Composable
fun MapOverlay(items: List<OverlayItem>, projection: MapProjection, modifier: Modifier = Modifier) {
    Layout(contents = items.map { it.content }, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { list -> list.map { it.measure(loose) } }
        layout(constraints.maxWidth, constraints.maxHeight) {
            items.forEachIndexed { i, item ->
                val p = projection.toScreen(item.position) ?: return@forEachIndexed
                placeables[i].forEach { pl ->
                    val x = p.x - pl.width * item.anchorX + item.offsetX.toPx()
                    val y = p.y - pl.height * item.anchorY + item.offsetY.toPx()
                    if (x > -pl.width && y > -pl.height && x < constraints.maxWidth && y < constraints.maxHeight) {
                        pl.place(x.toInt(), y.toInt())
                    }
                }
            }
        }
    }
}

/** A coloured round pin with a white ring and centre (posts and pins); bigger with an ink ring when selected. */
@Composable
fun DotMarker(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    val size = if (selected) 38.dp else 26.dp
    Box(
        Modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(if (selected) c.bgInverse else c.bgSurface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(if (selected) 9.dp else 7.dp).clip(CircleShape).background(c.bgSurface))
        }
    }
}

/** A neighbourhood node: rounded square in the node colour. */
@Composable
fun NodeMarker(label: String, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    Box(
        Modifier
            .size(28.dp)
            .shadow(2.dp, RoundedCornerShape(Tokens.Radius.sm))
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(c.stateNode)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(R.drawable.ic_node, tint = c.textInverse, size = 16.dp)
    }
}

/** A task: ink pill with the price. */
@Composable
fun TaskMarker(price: String, title: String, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    Row(
        Modifier
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(c.bgInverse)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title, $price" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HIcon(R.drawable.ic_tasks, tint = c.textInverse, size = 14.dp)
        Text(price, style = HarkaraTheme.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textInverse)
    }
}

/** A person sharing their location: initials in a live ring. */
@Composable
fun PersonMarker(initials: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) {
        Avatar(initials, size = 38.dp, live = true)
    }
}

/** "● Rafi · 38 min left" under a person. */
@Composable
fun PersonTag(text: String) {
    val c = HarkaraTheme.colors
    Row(
        Modifier
            .shadow(1.dp, CircleShape)
            .clip(CircleShape)
            .background(c.bgSurface)
            .border(1.dp, c.borderSubtle, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.stateOnline))
        Text(text, style = HarkaraTheme.type.label, color = c.textPrimary, maxLines = 1)
    }
}

/** This phone: a node-blue dot in a soft halo. */
@Composable
fun UserDot(label: String) {
    val c = HarkaraTheme.colors
    Box(Modifier.size(56.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.stateNode.copy(alpha = 0.16f)))
        Box(Modifier.size(18.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(c.bgSurface).padding(3.dp).clip(CircleShape).background(c.stateNode))
    }
}

/** Overlay items for [markers] in draw order, the person tags and the user dot on top. */
@Composable
fun markerItems(
    markers: List<MapMarker>,
    selectedId: String?,
    user: LatLng?,
    userLabel: String,
    personTag: (MapMarker) -> String,
    onSelect: (MapMarker) -> Unit,
): List<OverlayItem> {
    val c = HarkaraTheme.colors
    val items = ArrayList<OverlayItem>()
    if (user != null) items += OverlayItem("me", user) { UserDot(userLabel) }
    markers.sortedBy { it.id == selectedId }.forEach { m ->
        val label = listOfNotNull(m.label, m.detail).distinct().joinToString(", ").ifBlank { m.kind.name.lowercase() }
        items += when (m.kind) {
            MarkerKind.POST, MarkerKind.PIN -> OverlayItem(m.id, m.position) { DotMarker(c.markerColor(m.color), label, m.id == selectedId) { onSelect(m) } }
            MarkerKind.NODE -> OverlayItem(m.id, m.position) { NodeMarker(label) { onSelect(m) } }
            MarkerKind.TASK -> OverlayItem(m.id, m.position) { TaskMarker(m.label.orEmpty(), m.detail.orEmpty()) { onSelect(m) } }
            MarkerKind.PERSON -> OverlayItem(m.id, m.position) { PersonMarker(m.label.orEmpty(), label) { onSelect(m) } }
        }
        if (m.kind == MarkerKind.PERSON) {
            items += OverlayItem(m.id + ":tag", m.position, anchorX = 0.25f, anchorY = 0f, offsetY = 22.dp) { PersonTag(personTag(m)) }
        }
    }
    return items
}
