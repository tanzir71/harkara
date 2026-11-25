package io.github.tanzir71.harkara.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.tanzir71.harkara.places.Fix
import io.github.tanzir71.harkara.places.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

/** Resubscribing after a grant or resume must recheck permission, including approximate-only grants. */
internal fun visibleLocationFixes(
    source: LocationSource,
    lifecycle: Lifecycle,
    intervalMillis: Long,
    permitted: () -> Boolean,
): Flow<Fix?> = channelFlow {
    send(null)
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (permitted()) {
            send(source.lastKnown())
            source.updates(intervalMillis).collect { send(it) }
        } else {
            send(null)
        }
    }
}

internal data class ScreenLocation(val fix: Fix?, val locate: () -> Unit)

private fun locationPermitted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** No prompt on composition, no collection while disabled/backgrounded, and no service or timer. */
@Composable
internal fun rememberScreenLocation(source: LocationSource, enabled: Boolean, intervalMillis: Long): ScreenLocation {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionRevision by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
    }
    val updates = remember(source, context, lifecycle, enabled, intervalMillis, permissionRevision) {
        if (enabled) visibleLocationFixes(source, lifecycle, intervalMillis) { locationPermitted(context) }
        else flowOf(null)
    }
    val fix by updates.collectAsState(initial = null)
    return ScreenLocation(if (enabled) fix else null) {
        if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (locationPermitted(context)) permissionRevision++
            else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
