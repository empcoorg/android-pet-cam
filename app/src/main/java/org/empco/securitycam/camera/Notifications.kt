package org.empco.securitycam.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.empco.securitycam.MainActivity
import org.empco.securitycam.R

object Notifications {
    const val CHANNEL_SERVICE = "securitycam_service"
    const val CHANNEL_ALERTS = "securitycam_alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the camera service is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Avisos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reanudar cámara y actualizaciones"
            }
        )
    }

    fun serviceNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, CameraService::class.java).setAction(CameraService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, "Stop", stop)
            .build()
    }
}
