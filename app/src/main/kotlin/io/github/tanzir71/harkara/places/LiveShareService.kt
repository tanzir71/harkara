package io.github.tanzir71.harkara.places

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tanzir71.harkara.HarkaraApplication
import io.github.tanzir71.harkara.MainActivity
import io.github.tanzir71.harkara.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Keeps a live share the user started running with the screen off (foreground service, type location).
 * It exists only between Start and Stop/end; a notification shows who can see you and has a Stop button.
 */
class LiveShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as HarkaraApplication).container
        if (intent?.action == ACTION_STOP) {
            scope.launch { container.liveShareRepository.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        val share = container.liveShareRepository.outgoing.value
        if (share == null || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, share.recipients.size, share.end), type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.tanzir71.harkara.STOP_LIVE_SHARE"
        private const val CHANNEL = "live_share"
        private const val NOTIFICATION_ID = 9471

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private fun notification(context: Context, people: Int, end: Long): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(
                context, 1, Intent(context, LiveShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end * 1000))
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_locate)
                .setContentTitle(context.getString(R.string.live_notification_title))
                .setContentText(context.getString(R.string.live_notification_body, people, until))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(R.drawable.ic_close, context.getString(R.string.live_stop), stop)
                .build()
        }
    }
}

/** Starts and stops [LiveShareService] around a share. */
class ServiceLiveShareHost(private val context: Context) : LiveShareHost {
    override fun started(share: OutgoingShare) {
        if (!LiveShareService.hasLocationPermission(context)) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveShareService::class.java)) }
    }

    override fun stopped() {
        runCatching { context.stopService(Intent(context, LiveShareService::class.java)) }
    }
}
