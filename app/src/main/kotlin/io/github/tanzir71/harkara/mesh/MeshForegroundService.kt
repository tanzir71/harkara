package io.github.tanzir71.harkara.mesh

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.MainActivity
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.settings.Module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs only after an explicit mesh/node opt-in; never restarts radios after a process death. */
class MeshForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.offgrid_notification), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(601, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(getString(R.string.offgrid_notification)).setContentText(getString(R.string.offgrid_notification_body))
            .setContentIntent(open).setOngoing(true).build())
        val container = (application as HarkaraApplication).container
        scope.launch {
            container.settings.state.collect { state ->
                val wantsMesh = state.onboarded && state.isOn(Module.MESH) && !state.isOn(Module.LITE_MODE)
                val mesh = wantsMesh && MeshPermissions.granted(this@MeshForegroundService)
                if (wantsMesh && !mesh) container.settings.setModule(Module.MESH, false)
                val node = state.onboarded && state.isOn(Module.NODE)
                if (mesh || node) container.startRelays()
                if (mesh) {
                    container.mesh.start(if (state.meshMode == io.github.tanzir71.harkara.settings.MeshMode.EVENT) io.github.tanzir71.harkara.core.mesh.MeshProfile.EVENT else io.github.tanzir71.harkara.core.mesh.MeshProfile.SAVER)
                } else container.mesh.stop()
                if (node) { container.startLan(); container.node.startNode() } else container.node.stopNode(container.foreground)
                if (!mesh && !node) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        val container = (application as HarkaraApplication).container
        container.mesh.stop()
        container.node.stopNode(container.foreground)
        if (!container.foreground) container.stopRelays()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "harkara-offgrid" }
}
