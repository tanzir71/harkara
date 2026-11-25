package io.github.tanzir71.harkara.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.tools.QrCameraFactory
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** Explicit, permission-gated camera scan. Paste remains available after denial or camera failure. */
@Composable
fun QrScanControl(onScanned: (String) -> Unit, modifier: Modifier = Modifier, cameraFactory: QrCameraFactory = QrCameraFactory.Android) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val c = HarkaraTheme.colors
    var scanning by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        scanning = granted
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning) {
            Text(stringResource(R.string.qr_scan_hint), style = HarkaraTheme.type.caption, color = c.textSecondary)
            val description = stringResource(R.string.qr_scan_viewfinder)
            QrScannerPreview(
                onRead = { text ->
                    scanning = false
                    if (text.isNotBlank() && text.length <= MAX_SCANNED_CHARACTERS) onScanned(text) else failed = true
                },
                onError = { scanning = false; failed = true },
                cameraFactory = cameraFactory,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(Tokens.Radius.lg))
                    .background(c.bgSunken).semantics { contentDescription = description },
            )
            HButton(stringResource(R.string.qr_scan_stop), { scanning = false }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
        } else {
            HButton(stringResource(R.string.qr_scan_start), {
                focus.clearFocus()
                keyboard?.hide()
                denied = false
                failed = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                else permission.launch(Manifest.permission.CAMERA)
            }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_qr)
        }
        if (denied || failed) Text(stringResource(if (denied) R.string.qr_scan_permission else R.string.qr_scan_failed), style = HarkaraTheme.type.caption, color = c.textSecondary)
    }
}

const val MAX_SCANNED_CHARACTERS = 8000

/** Starts only on RESUMED; pause, disposal and a successful scan release the decoder and camera. */
@Composable
internal fun QrScannerPreview(onRead: (String) -> Unit, onError: () -> Unit, cameraFactory: QrCameraFactory, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val camera = remember(context, cameraFactory) { cameraFactory.create(context) }
    val read by rememberUpdatedState(onRead)
    val error by rememberUpdatedState(onError)
    AndroidView(factory = { camera.view }, modifier = modifier)
    DisposableEffect(camera, lifecycle) {
        var running = false
        var delivered = false
        var generation = 0L
        fun stop() { generation++; running = false; camera.stop() }
        fun fail(expected: Long) {
            if (running && !delivered && expected == generation) { delivered = true; stop(); error() }
        }
        fun start() {
            if (running || delivered) return
            val expected = ++generation
            running = true
            runCatching {
                camera.start({ text ->
                    if (running && !delivered && expected == generation) { delivered = true; stop(); read(text) }
                }, { fail(expected) })
            }.onFailure { fail(expected) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) start()
            if (event == Lifecycle.Event.ON_PAUSE) stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
}
