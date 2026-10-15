package org.empco.petcam

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import org.empco.petcam.camera.Notifications
import java.util.concurrent.Executors

class PetCamApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        org.empco.petcam.system.NetworkPicker.init(this)
        org.empco.petcam.camera.Keeper.scheduleWatchdog(this)
    }

    /** One upload at a time: parallel uploads on a 2 GB phone starve the camera pipeline. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(1))
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
