package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.settings.MeshMode
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.carrierColor
import io.github.tanzir71.harkara.ui.components.carrierIcon
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** One rung row in the connectivity sheet. */
data class RungRow(val carrier: Carrier, val title: String, val detail: String, val status: String, val inUse: Boolean)

/** Builds the sheet rows from the live state; the first available rung is "In use". */
@Composable
fun rungRows(state: ConnectivityState): List<RungRow> {
    val best = state.best
    @Composable
    fun status(carrier: Carrier): String = when {
        carrier == Carrier.SAVED -> stringResource(R.string.conn_status_waiting, state.waiting)
        carrier == best -> stringResource(R.string.conn_status_in_use)
        carrier == Carrier.QR -> stringResource(R.string.conn_status_always)
        carrier in state.available -> stringResource(R.string.conn_status_ready)
        else -> stringResource(R.string.conn_status_off)
    }
    @Composable
    fun row(carrier: Carrier, @StringRes title: Int, detail: String) = RungRow(carrier, stringResource(title), detail, status(carrier), carrier == best)
    return listOf(
        row(Carrier.ONLINE, R.string.conn_relays, stringResource(R.string.conn_relays_detail, state.relaysReachable, state.relaysConfigured)),
        row(Carrier.BRIDGE, R.string.conn_bridge, stringResource(R.string.conn_bridge_detail)),
        row(Carrier.NODE, R.string.conn_node, stringResource(R.string.conn_node_detail)),
        row(Carrier.MESH, R.string.conn_mesh, stringResource(R.string.conn_mesh_detail, state.meshPeers)),
        row(Carrier.SMS, R.string.conn_sms, stringResource(R.string.conn_sms_detail)),
        row(Carrier.QR, R.string.conn_qr, stringResource(R.string.conn_qr_detail)),
        row(Carrier.SAVED, R.string.conn_saved, stringResource(R.string.conn_saved_detail, state.waiting)),
    )
}

/** Screen 08: how messages get out, tried top to bottom, plus the mesh mode switch. */
@Composable
fun ConnectivitySheetContent(
    state: ConnectivityState,
    meshMode: MeshMode,
    onMeshMode: (MeshMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.conn_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.conn_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        Spacer(Modifier.size(8.dp))
        rungRows(state).forEach { row -> RungRowView(row) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.conn_mesh_mode), style = HarkaraTheme.type.headline, color = c.textPrimary)
                Text(stringResource(R.string.conn_mesh_mode_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
            }
            Segmented(
                listOf(stringResource(R.string.mesh_saver), stringResource(R.string.mesh_event)),
                MeshMode.entries.indexOf(meshMode),
                { onMeshMode(MeshMode.entries[it]) },
            )
        }
    }
}

@Composable
private fun RungRowView(row: RungRow) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (row.inUse) c.bgSurface else Color.Transparent)
            .then(if (row.inUse) Modifier.border(1.dp, c.borderDefault, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
            HIcon(carrierIcon(row.carrier), tint = c.carrierColor(row.carrier), size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = HarkaraTheme.type.body.copy(fontSize = HarkaraTheme.type.headline.fontSize), color = c.textPrimary)
            Text(row.detail, style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        Text(
            row.status,
            style = HarkaraTheme.type.label,
            color = if (row.inUse) c.carrierColor(row.carrier) else c.textSecondary,
        )
    }
}

/** Panic wipe confirmation (bottom sheet content, not a dialog). */
@Composable
fun PanicWipeSheetContent(onWipe: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(R.drawable.ic_sos, tint = c.stateSos)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.panic_confirm_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.panic_confirm_body), style = HarkaraTheme.type.body, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.cancel), onCancel, Modifier.weight(1f), variant = HButtonVariant.Secondary, height = 52.dp)
            HButton(stringResource(R.string.panic_confirm_wipe), onWipe, Modifier.weight(1f), variant = HButtonVariant.Primary, height = 52.dp)
        }
    }
}
