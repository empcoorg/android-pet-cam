package org.empco.petcam.camera

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.empco.petcam.MainActivity
import org.empco.petcam.R
import org.empco.petcam.settings.Settings
import org.empco.petcam.system.AppUpdater
import java.util.concurrent.TimeUnit

/**
 * Keeps the camera running unattended: after boot, after an app update, and whenever the service
 * has died (checked every 15 minutes). Android only lets a camera service start while the app is
 * in the foreground, so the keeper brings the activity up (full-screen intent, which opens
 * directly when the screen is off) and the activity starts the camera, then steps aside.
 */
object Keeper {
    private const val TAG = "Keeper"
    private const val PREFS = "petcam_keeper"
    private const val NOTIF_RESUME = 7
    const val EXTRA_FROM_KEEPER = "fromKeeper"

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("wanted", wanted).apply()
    }

    fun isWanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wanted", true)

    fun scheduleWatchdog(context: Context) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun ensureRunning(context: Context, why: String) {
        if (CameraService.instance != null) return
        if (!isWanted(context) || !Settings.get(context).current.autoStart) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        Log.w(TAG, "camera not running ($why); bringing it back")
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_FROM_KEEPER, true)
        // 1) Direct activity start (allowed right after boot/update on most builds, and with overlay permission).
        try { context.startActivity(open) } catch (e: Exception) { Log.w(TAG, "direct start refused: ${e.message}") }
        // 2) Full-screen notification: opens the activity immediately when the screen is off.
        val pi = PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reanudando monitor")
            .setContentText("Tap to continue")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try { NotificationManagerCompat.from(context).notify(NOTIF_RESUME, n) } catch (_: SecurityException) { }
        }
    }

    fun clearResumeNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_RESUME)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Keeper.scheduleWatchdog(context)
        Keeper.ensureRunning(context, intent.action ?: "broadcast")
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Keeper.ensureRunning(applicationContext, "watchdog")
        AppUpdater.check(applicationContext)
        return Result.success()
    }
}
