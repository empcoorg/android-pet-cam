package org.empco.petcam.system

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.Request
import org.empco.petcam.BuildConfig
import org.empco.petcam.R
import org.empco.petcam.camera.Notifications
import org.empco.petcam.cloud.Http
import org.empco.petcam.settings.Settings
import org.json.JSONObject
import java.io.File

/**
 * Over-the-air updates from the Worker. Every 6 h the phone asks for the published version; if it is
 * newer it downloads the APK and shows an "install" notification (Android always asks a person to
 * confirm a sideloaded install).
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val NOTIF_ID = 8
    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun checkInBackground(context: Context) {
        val app = context.applicationContext
        Thread({ check(app) }, "petcam-update").start()
    }

    @Synchronized
    fun check(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("petcam_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < INTERVAL_MS) return
        val cfg = Settings.get(context).current
        if (!cfg.cloudConfigured) return
        prefs.edit().putLong("lastCheck", now).apply()
        try {
            val base = Http.normalizeBase(cfg.cloudUrl)
            val auth = "Bearer ${cfg.deviceToken}"
            val latest = Http.client.newCall(
                Request.Builder().url("$base/api/device/app/version").header("Authorization", auth).build()
            ).execute().use { r ->
                if (!r.isSuccessful) return
                JSONObject(r.body?.string() ?: "{}").optInt("versionCode", 0)
            }
            if (latest <= BuildConfig.VERSION_CODE) return
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val apk = File(dir, "petcam-update-$latest.apk")
            if (!apk.exists() || apk.length() == 0L) {
                val tmp = File(dir, "download.tmp")
                Http.client.newCall(
                    Request.Builder().url("$base/api/device/app/apk").header("Authorization", auth).build()
                ).execute().use { r ->
                    if (!r.isSuccessful) return
                    tmp.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) ?: return }
                }
                if (!tmp.renameTo(apk)) return
            }
            notifyInstall(context, apk, latest)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
        }
    }

    private fun notifyInstall(context: Context, apk: File, version: Int) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 4, install, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Actualización disponible")
            .setContentText("Tap to install.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) } catch (_: SecurityException) { }
        }
    }
}
