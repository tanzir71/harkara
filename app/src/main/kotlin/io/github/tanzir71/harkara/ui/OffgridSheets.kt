package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ShareAppSheet(container: AppContainer, platform: PlatformActions) {
    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { container.node.startSharing() }
        while (true) { address = withContext(Dispatchers.IO) { container.node.address() }; delay(2000) }
    }
    DisposableEffect(Unit) { onDispose { container.node.stopSharing(); if (!container.foreground) container.stopLan() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.share_local_title), style = HarkaraTheme.type.title, color = HarkaraTheme.colors.textPrimary)
        Text(stringResource(R.string.share_local_body), style = HarkaraTheme.type.body, color = HarkaraTheme.colors.textSecondary)
        address?.let { url ->
            QrCode(url, stringResource(R.string.share_local_title), Modifier.size(216.dp))
            Text(url, style = HarkaraTheme.type.mono, color = HarkaraTheme.colors.textPrimary)
            HButton(stringResource(R.string.share_local_title), { platform.shareText(url) })
        } ?: Text(stringResource(R.string.share_local_wait), color = HarkaraTheme.colors.textSecondary)
        HButton(stringResource(R.string.share_online), { platform.shareText(io.github.tanzir71.harkara.update.UpdateChecker.DOWNLOAD_PAGE) })
    }
}

@Composable
internal fun RadarSheet(container: AppContainer) {
    val status by container.mesh.status.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.module_radar), style = HarkaraTheme.type.title, color = HarkaraTheme.colors.textPrimary)
        Text(stringResource(R.string.mesh_beta_note), color = HarkaraTheme.colors.textSecondary)
        if (status.peers.isEmpty()) Text(stringResource(R.string.radar_empty), color = HarkaraTheme.colors.textSecondary)
        status.peers.forEach { peer ->
            Text(stringResource(R.string.radar_peer, peer.presence ?: "----", peer.radio, peer.rssi ?: 0), color = HarkaraTheme.colors.textPrimary)
            Text("${peer.framesIn}", style = HarkaraTheme.type.mono, color = HarkaraTheme.colors.textSecondary)
        }
    }
}
