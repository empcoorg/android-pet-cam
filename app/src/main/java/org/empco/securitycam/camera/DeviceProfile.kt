package org.empco.securitycam.camera

import android.app.ActivityManager
import android.content.Context

/** Decides whether to run the reduced-load profile (Android Go / < 3 GB RAM phones). */
object DeviceProfile {
    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || mi.totalMem < 3L * 1024 * 1024 * 1024
    }
}
