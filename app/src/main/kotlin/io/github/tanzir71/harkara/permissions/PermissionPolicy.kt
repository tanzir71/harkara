package io.github.tanzir71.harkara.permissions

import android.Manifest

/** Computes Android runtime permissions needed for a Harkara mode. */
object PermissionPolicy {
    /** Returns required runtime permissions for [apiLevel]. */
    fun requiredPermissions(apiLevel: Int, geoFeaturesEnabled: Boolean): List<String> = buildList {
        if (apiLevel >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (apiLevel <= 30 || geoFeaturesEnabled) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (apiLevel >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
