package io.github.tanzir71.harkara.permissions

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun `api 31 and above requires bluetooth runtime permissions`() {
        val permissions = PermissionPolicy.requiredPermissions(apiLevel = 31, geoFeaturesEnabled = false)

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }

    @Test
    fun `api 33 and above requires notification permission`() {
        val permissions = PermissionPolicy.requiredPermissions(apiLevel = 33, geoFeaturesEnabled = false)

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }

    @Test
    fun `location permission is only required when geo features are enabled on modern android`() {
        val withoutGeo = PermissionPolicy.requiredPermissions(apiLevel = 35, geoFeaturesEnabled = false)
        val withGeo = PermissionPolicy.requiredPermissions(apiLevel = 35, geoFeaturesEnabled = true)

        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in withoutGeo)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in withGeo)
    }
}
