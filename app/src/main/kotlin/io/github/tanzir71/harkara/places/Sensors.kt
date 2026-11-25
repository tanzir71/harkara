package io.github.tanzir71.harkara.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.tanzir71.harkara.core.geo.Bearings
import io.github.tanzir71.harkara.core.geo.LatLng
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** A position fix. */
data class Fix(val position: LatLng, val accuracyMeters: Int?, val timeMillis: Long)

/**
 * Where the phone is. Updates run only while someone collects them: the live share the user started
 * (behind its foreground service) or the map/compass screen while it is visible. Faked in tests.
 */
interface LocationSource {
    fun updates(intervalMillis: Long): Flow<Fix>
    fun lastKnown(): Fix?
}

/** Compass heading in degrees clockwise from true north. Sensors run only while collected. */
interface HeadingSource {
    val available: Boolean
    fun headings(at: LatLng?): Flow<Double>
}

/** LocationManager (GPS, network and, on Android 12+, the platform's fused provider); no Play Services. */
class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(lm: LocationManager): List<String> {
        val all = lm.getProviders(true)
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return preferred.filter { it in all }
    }

    @SuppressLint("MissingPermission")
    override fun updates(intervalMillis: Long): Flow<Fix> = callbackFlow {
        // Check on collection, not when a screen first remembers this cold flow.
        val lm = manager
        if (lm == null || !granted()) {
            close()
            return@callbackFlow
        }
        // All four callbacks are spelled out: before Android 11 they are abstract, not default methods.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val used = providers(lm)
        used.forEach { runCatching { lm.requestLocationUpdates(it, intervalMillis, 10f, listener, Looper.getMainLooper()) } }
        lastKnown()?.let { trySend(it) }
        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }

    @SuppressLint("MissingPermission")
    override fun lastKnown(): Fix? {
        val lm = manager ?: return null
        if (!granted()) return null
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()?.toFix()
    }

    private fun Location.toFix() = Fix(LatLng(latitude, longitude), if (hasAccuracy()) accuracy.toInt() else null, time)
}

/** Rotation-vector sensor (falls back to accelerometer + magnetometer), corrected to true north. */
class SensorHeadingSource(context: Context) : HeadingSource {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    override val available: Boolean get() = rotation != null || (accel != null && magnet != null)

    override fun headings(at: LatLng?): Flow<Double> {
        val sm = sensors ?: return emptyFlow()
        if (!available) return emptyFlow()
        val declination = at?.let { GeomagneticField(it.lat.toFloat(), it.lng.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0
        return callbackFlow {
            val matrix = FloatArray(9)
            val orientation = FloatArray(3)
            val gravity = FloatArray(3)
            val geomagnetic = FloatArray(3)
            var haveG = false
            var haveM = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(matrix, event.values)
                        Sensor.TYPE_ACCELEROMETER -> { event.values.copyInto(gravity, endIndex = 3); haveG = true; if (!haveM) return }
                        Sensor.TYPE_MAGNETIC_FIELD -> { event.values.copyInto(geomagnetic, endIndex = 3); haveM = true; if (!haveG) return }
                    }
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR && !SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)) return
                    SensorManager.getOrientation(matrix, orientation)
                    val magnetic = Math.toDegrees(orientation[0].toDouble())
                    trySend(Bearings.trueHeading(magnetic, declination))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (rotation != null) {
                sm.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            } else {
                sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
                sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI)
            }
            awaitClose { sm.unregisterListener(listener) }
        }
    }
}
