package io.github.tanzir71.harkara.places

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.tanzir71.harkara.MainActivity
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.location.CheckIn

/** Shows an overdue alert from someone the user watches, if notifications are allowed. */
object CheckInAlerts {
    private const val CHANNEL = "trip_alerts"

    @SuppressLint("MissingPermission")
    fun notify(context: Context, alert: CheckIn) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.trip_alert_channel), NotificationManager.IMPORTANCE_HIGH))
        val who = alert.name ?: alert.author.take(8)
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(context.getString(R.string.trip_alert_title, who))
            .setContentText(context.getString(R.string.trip_alert_body, alert.destination.orEmpty(), alert.place))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(alert.id.hashCode(), notification) }
    }
}
