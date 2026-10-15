package org.empco.petcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.empco.petcam.camera.CameraService
import org.empco.petcam.camera.Keeper
import org.empco.petcam.settings.AppLock
import org.empco.petcam.settings.Settings
import org.empco.petcam.storage.ClipStore
import org.empco.petcam.ui.PetCamTheme
import org.empco.petcam.ui.ClipsScreen
import org.empco.petcam.ui.HomeScreen
import org.empco.petcam.ui.PinLockScreen
import org.empco.petcam.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val settings by lazy { Settings.get(this) }
    private val store by lazy { ClipStore(this) }
    private val appLock by lazy { AppLock.get(this) }
    private var blackout by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private val keeperHandler = Handler(Looper.getMainLooper())
    private var keeperDismiss: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stops Android's own resume-time "task snapshot" (a cached screenshot of the app's last
        // frame, shown briefly while the real content loads) and the Recent Apps thumbnail from
        // ever showing camera/footage content — both would otherwise bypass the PIN screen entirely.
        // Also blocks ordinary screenshots/screen recording of the app as a side effect.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        handleKeeper(intent)
        // Resume recording even behind a locked PIN screen — the PIN gates the UI/footage, not
        // whether the camera keeps running. Without this, the camera only restarts once someone
        // enters the correct PIN, since the equivalent check in App() only runs once unlocked.
        if (CameraService.instance == null && hasCameraPermission() && settings.current.autoStart) {
            CameraService.start(this)
        }
        unlocked = !appLock.isEnabled
        setContent {
            PetCamTheme {
                if (unlocked) {
                    App()
                    if (blackout) BlackoutOverlay { applyBlackout(false) }
                } else {
                    PinLockScreen(appLock) { unlocked = true }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleKeeper(intent)
    }

    /**
     * Opened by the keeper (boot, update, watchdog): show over the lock screen just long enough for
     * the camera service to start in the foreground, then get out of the way.
     */
    private fun handleKeeper(i: Intent?) {
        // MainActivity is singleTask, so this same instance (and this same Handler) can be reused
        // by a much later, unrelated launch — including a normal tap on the icon. Without this
        // cancellation, a stale dismiss callback from an earlier keeper-triggered wake could fire
        // minutes later and silently background an active, user-initiated session.
        keeperDismiss?.let { keeperHandler.removeCallbacks(it) }
        keeperDismiss = null
        if (i?.getBooleanExtra(Keeper.EXTRA_FROM_KEEPER, false) != true) return
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Keeper.clearResumeNotice(this)
        if (hasCameraPermission() && settings.current.autoStart && CameraService.instance == null) {
            CameraService.start(this)
        }
        val dismiss = Runnable {
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false) }
            moveTaskToBack(true)
        }
        keeperDismiss = dismiss
        keeperHandler.postDelayed(dismiss, 6_000)
    }

    override fun onStart() {
        super.onStart()
        CameraService.previewWanted.value = !blackout
    }

    override fun onStop() {
        super.onStop()
        CameraService.previewWanted.value = false
        // Re-arm the lock whenever the app actually leaves the foreground (not for the in-app
        // "screen off" blackout, which doesn't stop the Activity) so a picked-up, already-unlocked
        // phone can't just reopen the app and see footage without the PIN again.
        if (appLock.isEnabled) unlocked = false
    }

    /**
     * "Screen off": keep the window alive but draw it black at minimum backlight and hide the
     * system bars. Recording continues in the foreground service. A tap restores the UI.
     * (The power button also works: the service keeps recording while the phone is locked.)
     */
    private fun applyBlackout(on: Boolean) {
        blackout = on
        val lp = window.attributes
        lp.screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        CameraService.previewWanted.value = !on
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    @Composable
    private fun App() {
        val status by CameraService.status.collectAsStateWithLifecycle()
        val preview by CameraService.preview.collectAsStateWithLifecycle()
        val config by settings.config.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(0) }
        var granted by remember { mutableStateOf(hasCameraPermission()) }
        val snackbar = remember { SnackbarHostState() }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasCameraPermission()
        }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(requiredPermissions()) }
        LaunchedEffect(granted) {
            if (granted && settings.current.autoStart && !CameraService.status.value.running) {
                CameraService.start(this@MainActivity)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Videocam, null) }, label = { Text("Camera") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Clips") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (!granted) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        Text("This app needs camera (and microphone for sound) access.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { launcher.launch(requiredPermissions()) }, Modifier.padding(top = 16.dp)) {
                            Text("Grant permissions")
                        }
                    }
                    return@Box
                }
                when (tab) {
                    0 -> HomeScreen(
                        status = status,
                        preview = preview,
                        config = config,
                        onStart = { CameraService.start(this@MainActivity) },
                        onStop = { CameraService.stop(this@MainActivity) },
                        onMode = { m -> settings.update { it.copy(mode = m) } },
                        onMediaType = { t -> settings.update { it.copy(mediaType = t) } },
                        onSnapshot = { CameraService.instance?.takePhoto() },
                        onBlackout = { applyBlackout(true) },
                    )
                    1 -> ClipsScreen(store, refreshKey = status.clipsRecorded + status.photosTaken, snackbar = snackbar)
                    else -> SettingsScreen(config, status.videoResolution) { block -> settings.update(block) }
                }
            }
        }
    }
}

@Composable
private fun BlackoutOverlay(onWake: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        // Nothing drawn on purpose: a fully black window at minimum backlight.
    }
}
