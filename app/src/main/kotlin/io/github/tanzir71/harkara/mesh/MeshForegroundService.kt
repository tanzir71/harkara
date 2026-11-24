package io.github.tanzir71.harkara.mesh

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground service placeholder for long-lived mesh transports. */
class MeshForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
