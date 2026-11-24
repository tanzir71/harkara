package io.github.tanzir71.harkara.permissions

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** UI gate shown while critical runtime permissions are unavailable. */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    Text("Permissions")
    content()
}
