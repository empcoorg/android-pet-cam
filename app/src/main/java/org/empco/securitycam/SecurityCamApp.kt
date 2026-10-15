package org.empco.securitycam

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import org.empco.securitycam.camera.Notifications
import java.util.concurrent.Executors

class SecurityCamApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        org.empco.securitycam.system.NetworkPicker.init(this)
        org.empco.securitycam.camera.Keeper.scheduleWatchdog(this)
    }

    /** One upload at a time: parallel uploads on a 2 GB phone starve the camera pipeline. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(1))
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
