package org.empco.petcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.empco.petcam.cloud.LiveLink
import org.empco.petcam.cloud.UploadWorker
import org.empco.petcam.settings.CaptureMode
import org.empco.petcam.settings.MediaType
import org.empco.petcam.settings.Config
import org.empco.petcam.settings.Lens
import org.empco.petcam.settings.Orientation
import org.empco.petcam.settings.Profile
import org.empco.petcam.settings.Settings
import org.empco.petcam.settings.VideoQuality
import org.empco.petcam.storage.ClipStore
import org.empco.petcam.system.AppUpdater
import org.empco.petcam.system.NetworkPicker
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns the camera for as long as the user has it "started".
 *
 * Use cases bound: VideoCapture (+ timestamp OverlayEffect), ImageAnalysis (motion detection,
 * in-app preview, live-view frames) and ImageCapture (high-quality stills). If the device cannot
 * run all three streams at once, ImageCapture is dropped and stills fall back to analysis frames.
 */
class CameraService : LifecycleService(), LiveLink.Listener {

    data class Status(
        val running: Boolean = false,
        val cameraReady: Boolean = false,
        val mode: CaptureMode = CaptureMode.MOTION,
        val recording: Boolean = false,
        val recordingSince: Long = 0L,
        val motion: Boolean = false,
        val motionScore: Float = 0f,
        val liveConnected: Boolean = false,
        val liveViewers: Int = 0,
        val stillsDegraded: Boolean = false,
        val clipsRecorded: Int = 0,
        val photosTaken: Int = 0,
        val usedBytes: Long = 0L,
        val freeBytes: Long = 0L,
        val storageLocation: String = "",
        val liveRotation: Int = 0,
        val lowEnd: Boolean = false,
        val audioOn: Boolean = false,
        val night: Boolean = false,
        val videoResolution: String = "",
        val stillResolution: String = "",
        val error: String? = null,
    )

    companion object {
        private const val TAG = "CameraService"
        const val ACTION_STOP = "org.empco.petcam.action.STOP"
        const val NOTIF_ID = 1
        private const val MAX_MOTION_CLIP_MS = 10 * 60_000L
        private const val WARMUP_MS = 1500L
        private const val RETENTION_INTERVAL_MS = 60_000L

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        private val _preview = MutableStateFlow<Bitmap?>(null)
        val preview: StateFlow<Bitmap?> = _preview

        /** Set by the UI when it is visible; preview bitmaps are only produced when true. */
        val previewWanted = MutableStateFlow(false)

        @Volatile var instance: CameraService? = null
            private set

        fun start(context: Context) {
            Keeper.setWanted(context, true)
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
        }

        /** User-initiated stop: the keeper will not restart the camera until it is started again. */
        fun stop(context: Context) {
            Keeper.setWanted(context, false)
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var settings: Settings
    private lateinit var store: ClipStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var overlay: TimestampOverlay? = null

    private var recording: Recording? = null
    private var recordingFile: File? = null
    @Volatile private var startingRecording = false
    @Volatile private var currentConfig: Config = Config()
    private val lowEnd: Boolean by lazy { DeviceProfile.isLowEnd(this) }
    private var recoveryPending = false
    private var recoveryBackoffMs = 5_000L
    private var inErrorEpisode = false
    private var recoveryAttempts = 0
    private var closing = false

    private val detector = MotionDetector()
    @Volatile private var lastMotionAt = -1L
    @Volatile private var cameraOpenedAt = 0L
    private var lastDetectAt = 0L
    private var lastPreviewAt = 0L
    private var lastLiveAt = 0L
    private var lastPhotoAt = -1L
    @Volatile private var photoInFlight = false
    @Volatile private var liveRotation = -1
    @Volatile private var pendingAnalysisStill: File? = null

    private val nightVision = NightVision { on ->
        mainHandler.post { update { copy(night = on) }; updateNotification() }
    }
    /** Motion peaks per recording file (path -> collector), for full-res detail stills. */
    private val peakCollectors = HashMap<String, DetailStills.Collector>()
    private var recordingStartWall = 0L
    @Volatile private var analysisAspect = 4f / 3f
    private val rungPrefs by lazy { getSharedPreferences("petcam_camera", MODE_PRIVATE) }
    private val ticker = object : Runnable {
        override fun run() {
            if (closing) return
            nightVision.evaluate()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private val liveLink = LiveLink(this)
    @Volatile private var liveViewers = 0
    // People-triggered audio: when the cloud sees 2+ people it sends setAudio on with a window;
    // audio stays on until the window expires (extended by new detections).
    @Volatile private var audioUntil = 0L
    private var periodicJob: Job? = null
    private var configJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings.get(this)
        store = ClipStore(this)
        currentConfig = settings.current
        acquireWakeLock()
        startForegroundCompat()
        _status.value = Status(
            running = true, mode = currentConfig.mode,
            freeBytes = store.freeBytes(), storageLocation = store.locationLabel, lowEnd = lowEnd,
        )

        configJob = lifecycleScope.launch { settings.config.collect { onConfigChanged(it) } }
        lifecycleScope.launch { openCamera() }
        periodicJob = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                pushLiveStatus()
                updateNotification()
            }
        }
        ioExecutor.execute { UploadWorker.enqueuePending(this) }
        refreshUsage()
        mainHandler.postDelayed(ticker, 1_000)
        AppUpdater.checkInBackground(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Keeper.setWanted(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Not sticky: a system restart would not have while-in-use camera access anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        mainHandler.removeCallbacks(ticker)
        teardown()
        instance = null
        _status.value = Status()
        _preview.value = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "petcam:camera").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val notif = Notifications.serviceNotification(this, "Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification() {
        // Deliberately static and uninformative — the notification title/icon are already generic
        // (see Notifications.kt), and a detailed status line here (mode, recording, resolution,
        // viewer count) would undo that by describing camera activity in plain text to anyone who
        // glances at the notification shade. Full status is still visible in-app and in the portal.
        val text = "Running"
        if (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, Notifications.serviceNotification(this, text))
            } catch (_: SecurityException) { }
        }
    }

    private fun update(block: Status.() -> Status) {
        val old = _status.value
        val new = old.block()
        _status.value = new
        if (old.recording != new.recording || old.motion != new.motion || old.mode != new.mode ||
            old.cameraReady != new.cameraReady || old.error != new.error
        ) {
            pushLiveStatus()
        }
    }

    private fun setError(msg: String) {
        Log.e(TAG, msg)
        mainHandler.post { update { copy(error = msg) }; updateNotification() }
    }

    // ---------------------------------------------------------------- camera binding

    private suspend fun openCamera() {
        try {
            val p = ProcessCameraProvider.awaitInstance(this)
            provider = p
            bindUseCases(p)
        } catch (e: Exception) {
            setError("Camera failed to start: ${e.message}")
        }
    }

    /** Surface rotation that makes the recorded video upright for the chosen mounting. */
    private fun rotationFor(o: Orientation): Int = when (o) {
        Orientation.PORTRAIT -> Surface.ROTATION_0
        Orientation.LANDSCAPE_LEFT -> Surface.ROTATION_90
        Orientation.UPSIDE_DOWN -> Surface.ROTATION_180
        Orientation.LANDSCAPE_RIGHT -> Surface.ROTATION_270
    }

    private fun bindUseCases(p: ProcessCameraProvider) {
        when {
            currentConfig.mediaType == MediaType.PHOTO_ONLY -> bindPhotoOnly(p)
            currentConfig.profile == Profile.MAX -> bindMax(p)
            else -> bindLegacy(p)
        }
    }

    /**
     * Full-res stills on a timer only — no VideoCapture, no live-preview stream, and (CONTINUOUS
     * trigger only) no ImageAnalysis either. For hardware too weak to run the normal video+analysis
     * pipeline without starving the main thread (see the ANR history that motivated this:
     * CameraService repeatedly failed to respond within Android's service-ANR window under
     * CPU/memory contention from the concurrent streams). MOTION trigger still needs a cheap
     * ImageAnalysis stream to know when to burst — lighter than full video mode, but not free.
     */
    @SuppressLint("RestrictedApi")
    private fun bindPhotoOnly(p: ProcessCameraProvider) {
        val cfg = currentConfig
        val rotation = rotationFor(cfg.orientation)
        val selector = if (cfg.lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val still = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(92)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            )
            .setTargetRotation(rotation)
            .build()
        val analysis: ImageAnalysis? = if (cfg.mode != CaptureMode.MOTION) null else ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { f -> onFrame(f) } }
        p.unbindAll()
        val cam = if (analysis != null) p.bindToLifecycle(this, selector, still, analysis)
        else p.bindToLifecycle(this, selector, still)
        afterBind(cam, null, analysis, still, null, cfg, null, still.attachedSurfaceResolution)
    }

    // ---------------------------------------------------------------- MAX profile: resolution ladder

    private data class Rung(val quality: VideoQuality, val analysis: Size, val still: Boolean)

    private class Built(
        val video: VideoCapture<Recorder>, val analysis: ImageAnalysis, val still: ImageCapture?, val overlay: TimestampOverlay,
    )

    private fun shortEdge(q: VideoQuality) = when (q) { VideoQuality.FHD -> 1080; VideoQuality.HD -> 720; VideoQuality.SD -> 480 }

    /** Best first. Video resolution wins over stills; full-res stills over a bigger analysis stream. */
    private fun maxLadder(ceiling: VideoQuality): List<Rung> {
        val all = listOf(
            Rung(VideoQuality.FHD, Size(640, 480), true),
            Rung(VideoQuality.FHD, Size(320, 240), true),
            Rung(VideoQuality.FHD, Size(320, 240), false),
            Rung(VideoQuality.HD, Size(640, 480), true),
            Rung(VideoQuality.HD, Size(320, 240), true),
            Rung(VideoQuality.HD, Size(320, 240), false),
        )
        val cap = shortEdge(if (ceiling == VideoQuality.SD) VideoQuality.HD else ceiling)
        return all.filter { shortEdge(it.quality) <= cap }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildRung(r: Rung, cfg: Config): Built {
        val rotation = rotationFor(cfg.orientation)
        val q = if (r.quality == VideoQuality.FHD) Quality.FHD else Quality.HD
        val bitrate = when (r.quality) {
            VideoQuality.FHD -> if (lowEnd) 6_000_000 else 10_000_000
            else -> if (lowEnd) 4_000_000 else 6_000_000
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .setAspectRatio(if (cfg.wideAspect) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9)
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        Camera2Interop.Extender(vb).setSessionCaptureCallback(nightVision.captureCallback)
        val video = vb.build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(r.analysis, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { f -> onFrame(f) } }
        val still = if (!r.still) null else ImageCapture.Builder()
            .setCaptureMode(if (lowEnd) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(95)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            )
            .setTargetRotation(rotation)
            .build()
        return Built(video, analysis, still, TimestampOverlay(cfg.cameraLabel))
    }

    private fun Size?.label(): String = if (this == null) "" else "${maxOf(width, height)}x${minOf(width, height)}"

    /**
     * Bind the highest rung whose streams really come out at the requested size (CameraX silently
     * shrinks streams that don't fit the hardware's combinations). The winning rung is cached per
     * lens/aspect/orientation so later restarts bind first time.
     */
    @SuppressLint("RestrictedApi")
    private fun bindMax(p: ProcessCameraProvider) {
        val cfg = currentConfig
        val selector = if (cfg.lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val rungs = maxLadder(cfg.quality)
        val cacheKey = "rung_${cfg.lens}_${cfg.wideAspect}_${cfg.quality}"
        val cached = rungPrefs.getInt(cacheKey, -1)
        val order = if (cached in rungs.indices) listOf(cached) + rungs.indices.filter { it != cached } else rungs.indices.toList()

        fun tryBind(i: Int): Pair<Camera, Built>? {
            val b = buildRung(rungs[i], cfg)
            p.unbindAll()
            val group = UseCaseGroup.Builder().addUseCase(b.video).addUseCase(b.analysis)
            b.still?.let { group.addUseCase(it) }
            return try {
                p.bindToLifecycle(this, selector, group.build()) to b
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "rung $i ${rungs[i]} not supported: ${e.message}")
                b.overlay.close(); null
            }
        }

        var bestIdx = -1; var bestScore = -1
        for (i in order) {
            val (cam, b) = tryBind(i) ?: continue
            val v = b.video.attachedSurfaceResolution
            val st = b.still?.attachedSurfaceResolution
            val vShort = v?.let { minOf(it.width, it.height) } ?: 0
            val stOk = b.still == null || (st != null && minOf(st.width, st.height) >= 1080)
            Log.i(TAG, "rung $i ${rungs[i]} -> video ${v.label()} still ${st.label()}")
            if (vShort >= shortEdge(rungs[i].quality) * 9 / 10 && stOk) {
                rungPrefs.edit().putInt(cacheKey, i).apply()
                afterBind(cam, b.video, b.analysis, if (b.still != null && stOk) b.still else null, b.overlay, cfg, v, st)
                return
            }
            val score = vShort * 10 + (if (b.still != null && stOk) 1 else 0)
            if (score > bestScore) { bestScore = score; bestIdx = i }
            b.overlay.close()
        }
        if (bestIdx < 0) {
            Log.w(TAG, "no MAX rung could be bound; falling back to the balanced pipeline")
            bindLegacy(p)
            return
        }
        val (cam, b) = tryBind(bestIdx) ?: run { bindLegacy(p); return }
        rungPrefs.edit().putInt(cacheKey, bestIdx).apply()
        val st = b.still?.attachedSurfaceResolution
        val stOk = st != null && minOf(st.width, st.height) >= 1080
        afterBind(cam, b.video, b.analysis, if (stOk) b.still else null, b.overlay, cfg, b.video.attachedSurfaceResolution, st)
    }

    // ---------------------------------------------------------------- legacy profiles

    @SuppressLint("RestrictedApi")
    private fun bindLegacy(p: ProcessCameraProvider) {
        val cfg = currentConfig
        val rotation = rotationFor(cfg.orientation)
        val selector = if (cfg.lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        // Floor at 720p. On this sensor 720p (and 480p) run at 30fps, while 1080p with the
        // recording+analysis+overlay streams falls back to 640x480 — so 720p30 is the optimum.
        val effectiveQuality = if (cfg.quality == VideoQuality.SD) VideoQuality.HD else cfg.quality
        val quality = when (effectiveQuality) {
            VideoQuality.SD -> Quality.SD
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
        }
        val bitrate = when (effectiveQuality) {
            VideoQuality.SD -> 2_000_000
            VideoQuality.HD -> if (lowEnd) 4_000_000 else 6_000_000
            VideoQuality.FHD -> 8_000_000
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setAspectRatio(if (cfg.wideAspect) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9)
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        val videoBuilder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        Camera2Interop.Extender(videoBuilder).setSessionCaptureCallback(nightVision.captureCallback)
        // Only push framerate when the user chose the lowest resolution; at HD/FHD a high fps
        // range would make the camera fall back to 640x480. Resolution wins for security.
        if (effectiveQuality == VideoQuality.SD) {
            maxFpsRange(cfg.lens)?.let { r ->
                Camera2Interop.Extender(videoBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r)
                Log.i(TAG, "target fps range = $r")
            }
        }
        val video = videoBuilder.build()

        // Stream budget is 2 at high res. Profiles pick which to keep:
        //  EVIDENCE   = video + overlay (burned timestamp), no analysis  -> 720p, no live/motion
        //  HIRES_LIVE = video + analysis (live/motion), no overlay       -> 720p/1080p, soft timestamp
        //  BALANCED   = video + analysis + overlay (+still)              -> 640x480, everything
        val highRes = cfg.highRes
        val analysisSize = if (cfg.highRes) Size(320, 240) else Size(640, 480)
        val analysis: ImageAnalysis? = if (cfg.noAnalysis) null else ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { f -> onFrame(f) } }

        val stillSize = if (lowEnd) Size(1600, 1200) else Size(4000, 3000)
        val still: ImageCapture? = if (highRes || lowEnd) null else ImageCapture.Builder()
            .setCaptureMode(if (lowEnd) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(if (lowEnd) 85 else 95)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(stillSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
            )
            .setTargetRotation(rotation)
            .build()

        val ov = TimestampOverlay(cfg.cameraLabel)

        p.unbindAll()
        fun group(vararg extra: androidx.camera.core.UseCase): UseCaseGroup {
            val g = UseCaseGroup.Builder().addUseCase(video)
            extra.forEach { g.addUseCase(it) }
            if (cfg.burnTimestamp) g.addEffect(ov.effect)
            return g.build()
        }
        var degraded = highRes || lowEnd   // no full-res still stream in high-res or low-end
        val cam = when {
            cfg.noAnalysis -> p.bindToLifecycle(this, selector, group())              // EVIDENCE: video(+overlay)
            still == null -> p.bindToLifecycle(this, selector, group(analysis!!))      // HIRES_LIVE / lowEnd BALANCED
            else -> try {
                p.bindToLifecycle(this, selector, group(analysis!!, still))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Video+Analysis+Still not supported, dropping ImageCapture", e)
                degraded = true
                p.bindToLifecycle(this, selector, group(analysis!!))
            }
        }

        afterBind(cam, video, analysis, if (degraded) null else still, ov, cfg,
            video.attachedSurfaceResolution, if (degraded) null else still?.attachedSurfaceResolution)
    }

    /** Common post-bind wiring for every profile. */
    private fun afterBind(
        cam: Camera, video: VideoCapture<Recorder>?, analysis: ImageAnalysis?, still: ImageCapture?,
        ov: TimestampOverlay?, cfg: Config, videoRes: Size?, stillRes: Size?,
    ) {
        val degraded = still == null
        videoCapture = video
        imageAnalysis = analysis
        imageCapture = still
        overlay = ov
        camera = cam
        cameraOpenedAt = SystemClock.elapsedRealtime()
        nightVision.bind(cam, cfg.night)
        detector.reset()
        // Never emit light: torch off always, and belt-and-braces re-assert if the state flips.
        try { cam.cameraControl.enableTorch(false) } catch (_: Exception) { }
        cam.cameraInfo.torchState.observe(this) { st ->
            if (st == androidx.camera.core.TorchState.ON) {
                try { cam.cameraControl.enableTorch(false) } catch (_: Exception) { }
            }
        }

        cam.cameraInfo.cameraState.observe(this) { st ->
            val err = st.error
            if (err != null && err.code != 0) {
                // One recovery per error episode; the observer can emit the same error repeatedly.
                if (!inErrorEpisode) {
                    inErrorEpisode = true
                    // Never give up: a dedicated camera must come back on its own (backoff caps at 5 min).
                    recoveryAttempts++
                    setError("Camera error (code ${err.code}), recovering (attempt $recoveryAttempts)…")
                    scheduleRecovery()
                    // Three error episodes in a row without ever reaching a stable OPEN (reset below)
                    // means this profile+lens combination doesn't just underperform here, it breaks
                    // the camera outright — distinct from (and worse than) a resolution shortfall.
                    if (recoveryAttempts >= 3) CapabilityCache.recordUnstable(this, cfg.profile, cfg.lens)
                }
            } else if (st.type == androidx.camera.core.CameraState.Type.OPEN) {
                inErrorEpisode = false
                recoveryAttempts = 0
                recoveryBackoffMs = 5_000L
            }
        }

        Log.i(TAG, "bound: video ${videoRes.label()} still ${stillRes.label().ifEmpty { "from analysis" }}")
        videoRes?.let { CapabilityCache.recordAchieved(this, cfg.profile, cfg.lens, minOf(it.width, it.height)) }
        update {
            copy(
                cameraReady = true, stillsDegraded = degraded, error = null, mode = cfg.mode,
                videoResolution = videoRes.label(), stillResolution = stillRes.label(), night = nightVision.active,
            )
        }
        updateNotification()
        if (cfg.mediaType == MediaType.PHOTO_ONLY) {
            startPhotoOnlyLoop()
        } else if (cfg.noAnalysis || cfg.mode == CaptureMode.CONTINUOUS) {
            startRecording()
        }
        startLiveLinkIfConfigured()
    }

    /** Highest available AE target-fps range for the selected lens (max framerate). */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun maxFpsRange(lens: Lens): Range<Int>? {
        return try {
            val cm = getSystemService(CameraManager::class.java) ?: return null
            val want = if (lens == Lens.FRONT) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != want) continue
                val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
                // Prefer the highest upper bound; among equals, a stable range (lower == upper).
                return ranges.filter { it.upper > 0 }
                    .maxWithOrNull(compareBy({ it.upper }, { if (it.lower == it.upper) 1 else 0 }))
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "maxFpsRange failed", e); null
        }
    }

    private fun unbindCamera() {
        stopPhotoOnlyLoop()
        nightVision.unbind()
        try { provider?.unbindAll() } catch (e: Exception) { Log.w(TAG, "unbind failed", e) }
        overlay?.close()
        overlay = null
        camera = null
        videoCapture = null
        imageCapture = null
        imageAnalysis = null
    }

    /** Re-open the camera after a HAL/system error, with backoff. */
    private fun scheduleRecovery() {
        if (recoveryPending || closing) return
        recoveryPending = true
        Log.w(TAG, "camera recovery in ${recoveryBackoffMs / 1000}s")
        mainHandler.postDelayed({
            recoveryPending = false
            if (closing) return@postDelayed
            rebind()
            recoveryBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(300_000L)
        }, recoveryBackoffMs)
    }

    /** Stop the current clip (if any), rebind with the current config. */
    private fun rebind() {
        val p = provider ?: return
        update { copy(cameraReady = false, recording = false) }
        recording?.stop()
        // Treat the old clip's Finalize as stale; its file is still processed by onFinalize.
        recording = null
        recordingFile = null
        startingRecording = false
        unbindCamera()
        try {
            bindUseCases(p)
        } catch (e: Exception) {
            setError("Camera failed to restart: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- frame analysis (analysis thread)

    private fun onFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val cfg = currentConfig
            if (now - cameraOpenedAt < WARMUP_MS) return

            pendingAnalysisStill?.let { file ->
                pendingAnalysisStill = null
                saveAnalysisStill(image, file)
            }

            if (now - lastDetectAt >= (if (lowEnd) 250 else 125)) { // 4 fps (low-end) / 8 fps motion analysis
                lastDetectAt = now
                val plane = image.planes[0]
                val score = detector.analyze(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                nightVision.onLuma(detector.meanLuma)
                analysisAspect = image.width.toFloat() / image.height.coerceAtLeast(1)
                val moving = score >= MotionDetector.thresholdFor(cfg.sensitivity)
                if (moving) lastMotionAt = now
                val box = if (moving) detector.lastBox else null
                mainHandler.post { onMotionEvaluated(moving, score, now, box) }
            }

            // Never add encode work while a still or a recording start is in flight.
            val busy = photoInFlight || startingRecording
            val wantPreview = !busy && previewWanted.value && now - lastPreviewAt >= (if (lowEnd) 250 else 100)
            val maxLiveFps = if (lowEnd) 4 else 15
            val wantLive = !busy && liveViewers > 0 && liveLink.connected &&
                now - lastLiveAt >= 1000L / cfg.liveFps.coerceIn(1, maxLiveFps)
            if (wantLive) {
                // Raw sensor-orientation JPEG straight from the YUV buffer (no bitmap decode/rotate);
                // the viewer rotates it with CSS using liveRotation from the status message.
                lastLiveAt = now
                val rot = image.imageInfo.rotationDegrees
                if (rot != liveRotation) {
                    liveRotation = rot
                    mainHandler.post { update { copy(liveRotation = rot) }; pushLiveStatus() }
                }
                liveLink.sendFrame(YuvUtil.toJpeg(image, if (lowEnd) 45 else 60))
            }
            if (wantPreview) {
                lastPreviewAt = now
                YuvUtil.toBitmap(image, if (lowEnd) 55 else 70)?.let { _preview.value = it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame processing failed", t)
        } finally {
            image.close()
        }
    }

    private val stampFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private val stampDowFmt = java.text.SimpleDateFormat("EEE", java.util.Locale.US)

    /** Burn date/time + label into a bitmap (software), for stills when not using the GL overlay. */
    private fun drawStamp(bmp: Bitmap, label: String, at: Long = System.currentTimeMillis()) {
        try {
            val c = Canvas(bmp)
            val size = (minOf(bmp.width, bmp.height) * 0.019f).coerceAtLeast(7f)
            val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                color = Color.WHITE; textSize = size; style = Paint.Style.FILL
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                color = Color.BLACK; textSize = size; style = Paint.Style.STROKE
                strokeWidth = size * 0.11f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                maskFilter = BlurMaskFilter(size * 0.035f, BlurMaskFilter.Blur.NORMAL)
            }
            val date = java.util.Date(at)
            val base = stampFmt.format(date) + " " + stampDowFmt.format(date).uppercase(java.util.Locale.US) + " " + utcOffsetLabel(at)
            val s = if (label.isBlank()) base else "$base  $label"
            val pad = size * 0.4f
            val x = pad * 2
            val y = bmp.height - pad * 2
            c.drawText(s, x, y, outline)
            c.drawText(s, x, y, text)
        } catch (e: Exception) { Log.w(TAG, "drawStamp failed", e) }
    }

    private fun saveAnalysisStill(image: ImageProxy, file: File) {
        try {
            var bmp = YuvUtil.toBitmap(image, 95) ?: return
            if (nightVision.active) bmp = NightVision.enhance(bmp)
            drawStamp(bmp, currentConfig.cameraLabel)
            file.outputStream().use { it.write(YuvUtil.bitmapToJpeg(bmp, 95)) }
            bmp.recycle()
            mainHandler.post {
                update { copy(photosTaken = photosTaken + 1) }
                afterFileWritten(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis still failed", e)
            file.delete()
        }
    }

    // ---------------------------------------------------------------- recording state machine (main thread)

    private fun withinMotionWindow(now: Long, cfg: Config): Boolean =
        lastMotionAt >= 0 && (now - lastMotionAt) < cfg.postMotionSeconds * 1000L

    private fun onMotionEvaluated(moving: Boolean, score: Float, now: Long, box: FloatArray? = null) {
        if (closing) return
        if (moving && recording != null && recordingStartWall > 0 && currentConfig.detailStills) {
            val path = recordingFile?.path
            if (path != null) {
                val wall = System.currentTimeMillis()
                peakCollectors.getOrPut(path) { DetailStills.Collector() }.add(
                    DetailStills.Peak(wall - recordingStartWall, wall, score, box, analysisAspect, nightVision.active)
                )
            }
        }
        val cfg = currentConfig
        val s = _status.value
        if (s.motion != moving || abs(score - s.motionScore) > 0.01f) {
            update { copy(motion = moving, motionScore = score) }
        }
        // PHOTO_ONLY + MOTION drives its own burst/tail cadence from this same motion state (see
        // startPhotoOnlyLoop) instead of the video-recording + "still every photoIntervalSeconds"
        // behavior below, which only makes sense when actually recording video.
        if (cfg.mode != CaptureMode.MOTION || cfg.mediaType != MediaType.VIDEO) return

        val active = withinMotionWindow(now, cfg)
        if (active && recording == null && !startingRecording) {
            Log.i(TAG, "motion start (score=${"%.3f".format(score)})")
            startRecording()
        } else if (!active && recording != null) {
            Log.i(TAG, "motion window ended, stopping clip")
            stopRecording()
        }
        if (moving && (lastPhotoAt < 0 || now - lastPhotoAt >= cfg.photoIntervalSeconds * 1000L)) {
            takePhoto()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null || startingRecording || closing || !_status.value.cameraReady) return
        val cfg = currentConfig
        val file = store.newVideoFile(cfg.mode)
        val limitMs = if (cfg.noAnalysis || cfg.mode == CaptureMode.CONTINUOUS) cfg.clipMinutes.coerceAtLeast(1) * 60_000L
        else MAX_MOTION_CLIP_MS
        val opts = FileOutputOptions.Builder(file).setDurationLimitMillis(limitMs).build()
        var pending = vc.output.prepareRecording(this, opts)
        val hasMic = hasPermission(Manifest.permission.RECORD_AUDIO)
        // Attach an audio track whenever we have the mic, even if currently silent, so it can be
        // un-muted mid-clip the instant people are detected.
        if (hasMic) pending = pending.withAudioEnabled()
        startingRecording = true
        recordingFile = file
        Log.i(TAG, "recording -> ${file.name}")
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev) }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    /** Audio is on when the user's sound setting is on, or a people-detection window is active. */
    private fun audioShouldBeOn(): Boolean =
        currentConfig.sound || SystemClock.elapsedRealtime() < audioUntil

    private fun applyMute() {
        val on = audioShouldBeOn()
        try { recording?.mute(!on) } catch (e: Exception) { Log.w(TAG, "mute() failed", e) }
        if (_status.value.audioOn != on) update { copy(audioOn = on) }
    }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                startingRecording = false
                recordingStartWall = System.currentTimeMillis()
                applyMute()
                update { copy(recording = true, recordingSince = SystemClock.elapsedRealtime()) }
                updateNotification()
            }
            is VideoRecordEvent.Finalize -> onFinalize(ev)
            else -> Unit
        }
    }

    private fun onFinalize(ev: VideoRecordEvent.Finalize) {
        val file = (ev.outputOptions as? FileOutputOptions)?.file
        val isCurrent = file != null && file == recordingFile
        if (ev.hasError()) Log.w(TAG, "finalize ${file?.name} error=${ev.error} cause=${ev.cause?.message}")

        val benign = !ev.hasError() || ev.error in setOf(
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        )
        val hasData = file != null && file.exists() && file.length() > 0 &&
            ev.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA

        val peaks = file?.let { peakCollectors.remove(it.path)?.take() }.orEmpty()
        if (hasData) {
            update { copy(clipsRecorded = clipsRecorded + 1) }
            afterFileWritten(file!!)
            if (peaks.isNotEmpty() && currentConfig.detailStills && !lowBattery()) {
                val label = currentConfig.cameraLabel
                // Same condition that decides whether bindLegacy attaches the burn-in GL effect to
                // this recording — if it did, these frames are pulled from a video that already has
                // a baked-in stamp, so drawing another one in extractDetails would double it up.
                val alreadyBurned = currentConfig.burnTimestamp && currentConfig.canBurnTimestamp
                ioExecutor.execute { extractDetails(file, peaks, label, alreadyBurned) }
            }
        } else {
            file?.delete()
        }

        if (!isCurrent) return // stale event from before a rebind
        recording = null
        recordingFile = null
        startingRecording = false
        update { copy(recording = false) }
        updateNotification()

        if (!benign) {
            setError("Recording error ${ev.error}; retrying")
            mainHandler.postDelayed({ if (!closing && _status.value.cameraReady && recording == null) startRecording() }, 5_000)
            return
        }
        if (closing || !_status.value.cameraReady) return
        val cfg = currentConfig
        val continueNow = cfg.noAnalysis || cfg.mode == CaptureMode.CONTINUOUS ||
            (cfg.mode == CaptureMode.MOTION && withinMotionWindow(SystemClock.elapsedRealtime(), cfg))
        if (continueNow) startRecording()
    }

    /** Upload + retention for a finished file. */
    private fun afterFileWritten(file: File) {
        // Queued straight away: the upload must never wait behind retention, which lists every file.
        UploadWorker.enqueue(this, file)
        val len = file.length()
        update { copy(usedBytes = usedBytes + len) }
        scheduleRetention()
    }

    private var retentionQueued = false
    private var lastRetentionAt = 0L

    /**
     * Coalesced retention: at most one pass queued, at most one per [RETENTION_INTERVAL_MS]. A pass
     * per file let slow passes (minutes each on a phone holding tens of thousands of files) pile up
     * on [ioExecutor] without bound while photos kept arriving every few seconds.
     */
    private fun scheduleRetention() {
        if (retentionQueued) return
        retentionQueued = true
        val wait = (lastRetentionAt + RETENTION_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        mainHandler.postDelayed({
            if (closing) return@postDelayed
            val cfg = currentConfig
            val exclude = recordingFile
            val s = store
            ioExecutor.execute {
                try {
                    val used = s.enforceLimit(cfg.storageLimitBytes, cfg.minFreePercent, exclude)
                    val free = s.freeBytes()
                    mainHandler.post { update { copy(usedBytes = if (used >= 0) used else usedBytes, freeBytes = free) } }
                } catch (e: Exception) {
                    Log.w(TAG, "retention failed", e)
                } finally {
                    mainHandler.post { retentionQueued = false; lastRetentionAt = SystemClock.elapsedRealtime() }
                }
            }
        }, wait)
    }

    /** Re-measure footage size off the main thread (a full listing). */
    private fun refreshUsage() {
        val s = store
        ioExecutor.execute {
            val used = s.usedBytes()
            val free = s.freeBytes()
            mainHandler.post { update { copy(usedBytes = used, freeBytes = free) } }
        }
    }

    // ---------------------------------------------------------------- stills

    fun takePhoto() {
        if (closing || !_status.value.cameraReady) return
        if (photoInFlight) return
        val now = SystemClock.elapsedRealtime()
        lastPhotoAt = now
        val file = store.newPhotoFile()
        val ic = imageCapture
        if (ic == null) {
            // Degraded path: snapshot the next analysis frame instead.
            pendingAnalysisStill = file
            return
        }
        photoInFlight = true
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                photoInFlight = false
                val night = nightVision.active
                val stamp = currentConfig.softTimestamp
                val label = currentConfig.cameraLabel
                val photoOnly = currentConfig.mediaType == MediaType.PHOTO_ONLY
                ioExecutor.execute {
                    if (night || stamp) finishStill(file, night, label)
                    // Photo-only mode has no live-preview stream of its own; each full-res photo
                    // doubles as the live frame so the portal's viewer shows the latest one.
                    if (photoOnly && liveViewers > 0) liveLink.sendFrame(file.readBytes())
                    mainHandler.post {
                        update { copy(photosTaken = photosTaken + 1) }
                        afterFileWritten(file)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                photoInFlight = false
                Log.w(TAG, "still capture failed", exception)
                file.delete()
            }
        })
    }

    private var photoOnlyJob: Job? = null

    /**
     * PHOTO_ONLY media, either trigger:
     *  - CONTINUOUS: fixed cadence forever (photoOnlyIntervalSeconds).
     *  - MOTION: bursts every photoBurstIntervalSeconds while [Status.motion] is true (kept current
     *    by the ImageAnalysis stream bound in bindPhotoOnly), then the slower photoOnlyIntervalSeconds
     *    cadence for photoOnlyPostMotionSeconds after the last detected motion, then nothing until
     *    motion resumes. Polls at a fixed short tick rather than reacting to motion events directly,
     *    which keeps both triggers' cadence logic in one place.
     */
    private fun startPhotoOnlyLoop() {
        if (photoOnlyJob?.isActive == true) return
        var lastShotAt = 0L
        photoOnlyJob = lifecycleScope.launch {
            while (isActive) {
                val cfg = currentConfig
                val now = SystemClock.elapsedRealtime()
                val cadenceMs: Long? = if (cfg.mode == CaptureMode.CONTINUOUS) {
                    cfg.photoOnlyIntervalSeconds.coerceIn(2, 300) * 1000L
                } else {
                    val sinceMotion = if (lastMotionAt >= 0) now - lastMotionAt else Long.MAX_VALUE
                    when {
                        _status.value.motion -> cfg.photoBurstIntervalSeconds.coerceIn(1, 60) * 1000L
                        sinceMotion < cfg.photoOnlyPostMotionSeconds.coerceAtLeast(0) * 1000L ->
                            cfg.photoOnlyIntervalSeconds.coerceIn(2, 300) * 1000L
                        else -> null
                    }
                }
                if (cadenceMs != null && now - lastShotAt >= cadenceMs) {
                    takePhoto()
                    lastShotAt = now
                }
                delay(500)
            }
        }
    }

    private fun stopPhotoOnlyLoop() {
        photoOnlyJob?.cancel()
        photoOnlyJob = null
    }

    private fun lowBattery(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        return !bm.isCharging && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15
    }

    /** Upright, timestamped and (at night) brightened version of a full-res still, written in place. */
    private fun finishStill(file: File, night: Boolean, label: String) {
        try {
            val exif = android.media.ExifInterface(file.absolutePath)
            val deg = when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true }
            var bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            if (deg != 0) {
                val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true).also { r.recycle() }
            }
            if (night && !lowBattery()) bmp = NightVision.enhance(bmp)
            drawStamp(bmp, label, store.capturedAt(file))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            bmp.recycle()
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "still post-processing failed; keeping original", e)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "still post-processing out of memory; keeping original")
        }
    }

    /** Runs on ioExecutor: full-res frames + motion crops from a finished clip. */
    private fun extractDetails(video: File, peaks: List<DetailStills.Peak>, label: String, alreadyBurned: Boolean) {
        DetailStills.extract(video, peaks) { r ->
            listOf(r.frame to "frame", r.crop to "detail").forEach { (b0, kind) ->
                if (b0 == null) return@forEach
                var b = b0
                try {
                    if (r.peak.night) b = NightVision.enhance(b)
                    if (!alreadyBurned) drawStamp(b, label, r.peak.wallTime)
                    val out = store.newPhotoFileAt(r.peak.wallTime, kind)
                    out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    mainHandler.post {
                        update { copy(photosTaken = photosTaken + 1) }
                        afterFileWritten(out)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "saving $kind failed", e)
                } finally {
                    b.recycle()
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings changes

    private fun onConfigChanged(cfg: Config) {
        val old = currentConfig
        currentConfig = cfg
        if (!_status.value.cameraReady) return

        if (old.mode != cfg.mode) {
            update { copy(mode = cfg.mode) }
            // PHOTO_ONLY's own trigger handling lives in startPhotoOnlyLoop/bindPhotoOnly (a rebind
            // below picks up the trigger change); this start/stop-recording pair is VIDEO-only.
            if (cfg.mediaType == MediaType.VIDEO) {
                when (cfg.mode) {
                    CaptureMode.CONTINUOUS -> if (recording == null) startRecording()
                    CaptureMode.MOTION -> if (recording != null &&
                        !withinMotionWindow(SystemClock.elapsedRealtime(), cfg)
                    ) stopRecording()
                }
            }
            updateNotification()
        }
        if (old.cameraLabel != cfg.cameraLabel) overlay?.label = cfg.cameraLabel
        if (old.night != cfg.night) nightVision.setMode(cfg.night)
        if (old.useSdCard != cfg.useSdCard) {
            store = ClipStore(this)
            update { copy(storageLocation = store.locationLabel) }
            refreshUsage()
        }
        // Switching VIDEO<->PHOTO_ONLY always needs a rebind (completely different use-case set).
        // Within PHOTO_ONLY, switching trigger also does: MOTION binds an extra ImageAnalysis
        // stream for its burst/tail logic, CONTINUOUS doesn't need one at all.
        val bindingChanged = old.mediaType != cfg.mediaType ||
            (cfg.mediaType == MediaType.PHOTO_ONLY && old.mode != cfg.mode)
        if (old.lens != cfg.lens || old.quality != cfg.quality ||
            old.orientation != cfg.orientation || old.wideAspect != cfg.wideAspect ||
            old.profile != cfg.profile || bindingChanged
        ) {
            rebind()
        } else if (old.sound != cfg.sound && recording != null) {
            // Audio is fixed per clip; finish this clip so the next one picks up the new setting.
            stopRecording()
        }
        if (old.cloudUrl != cfg.cloudUrl || old.deviceToken != cfg.deviceToken || old.liveEnabled != cfg.liveEnabled) {
            liveLink.stop()
            liveViewers = 0
            update { copy(liveViewers = 0, liveConnected = false) }
            startLiveLinkIfConfigured()
        }
    }

    // ---------------------------------------------------------------- live link

    private fun startLiveLinkIfConfigured() {
        val cfg = currentConfig
        if (cfg.liveEnabled && cfg.cloudConfigured) liveLink.start(cfg.cloudUrl, cfg.deviceToken)
    }

    override fun onLiveConnected(connected: Boolean) {
        mainHandler.post {
            update { copy(liveConnected = connected) }
            if (connected) pushLiveStatus()
        }
    }

    override fun onAuthRejected() {
        mainHandler.post { if (settings.adoptBuiltInToken()) Log.w(TAG, "live token rejected; using built-in token") }
    }

    override fun onViewers(count: Int) {
        mainHandler.post {
            liveViewers = count
            update { copy(liveViewers = count) }
            updateNotification()
            if (count > 0) pushLiveStatus()
        }
    }

    override fun onCommand(cmd: JSONObject) {
        mainHandler.post {
            when (cmd.optString("name")) {
                "setMode" -> {
                    val m = if (cmd.optString("value") == "continuous") CaptureMode.CONTINUOUS else CaptureMode.MOTION
                    settings.update { it.copy(mode = m) }
                }
                "setMediaType" -> {
                    val t = if (cmd.optString("value") == "photo_only") MediaType.PHOTO_ONLY else MediaType.VIDEO
                    settings.update { it.copy(mediaType = t) }
                }
                "setPhotoOnlyInterval" -> {
                    val secs = cmd.optInt("seconds", 10).coerceIn(2, 300)
                    settings.update { it.copy(photoOnlyIntervalSeconds = secs) }
                    pushLiveStatus()
                }
                "setPhotoBurstInterval" -> {
                    val secs = cmd.optInt("seconds", 5).coerceIn(1, 60)
                    settings.update { it.copy(photoBurstIntervalSeconds = secs) }
                    pushLiveStatus()
                }
                "setPhotoOnlyPostMotion" -> {
                    val secs = cmd.optInt("seconds", 60).coerceIn(0, 3600)
                    settings.update { it.copy(photoOnlyPostMotionSeconds = secs) }
                    pushLiveStatus()
                }
                "snapshot" -> takePhoto()
                "setOrientation" -> {
                    val o = when (cmd.optString("value")) {
                        "landscape_left" -> org.empco.petcam.settings.Orientation.LANDSCAPE_LEFT
                        "landscape_right" -> org.empco.petcam.settings.Orientation.LANDSCAPE_RIGHT
                        "upside_down" -> org.empco.petcam.settings.Orientation.UPSIDE_DOWN
                        else -> org.empco.petcam.settings.Orientation.PORTRAIT
                    }
                    settings.update { it.copy(orientation = o) }
                }
                "setAudio" -> {
                    val secs = cmd.optInt("seconds", 120).coerceIn(5, 1800)
                    if (cmd.optString("value") == "on") {
                        audioUntil = SystemClock.elapsedRealtime() + secs * 1000L
                        mainHandler.postDelayed({ applyMute() }, secs * 1000L + 500)
                    } else {
                        audioUntil = 0L
                    }
                    applyMute()
                    pushLiveStatus()
                }
                "status" -> pushLiveStatus()
            }
        }
    }

    private fun pushLiveStatus() {
        if (!liveLink.connected) return
        val s = _status.value
        val cfg = currentConfig
        val bm = getSystemService(BatteryManager::class.java)
        val net = NetworkPicker.status(this)
        val json = JSONObject()
            .put("type", "status")
            .put("label", cfg.cameraLabel)
            .put("mode", s.mode.name.lowercase())
            .put("mediaType", cfg.mediaType.name.lowercase())
            .put("cameraReady", s.cameraReady)
            .put("recording", s.recording)
            .put("motion", s.motion)
            .put("camera", cfg.lens.name.lowercase())
            .put("quality", cfg.quality.name)
            .put("battery", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", bm?.isCharging ?: false)
            .put("usedBytes", s.usedBytes)
            .put("freeBytes", s.freeBytes)
            .put("storage", s.storageLocation)
            .put("liveRotation", s.liveRotation)
            .put("lowEnd", s.lowEnd)
            .put("audioOn", s.audioOn)
            .put("highRes", currentConfig.highRes)
            .put("profile", currentConfig.profile.name.lowercase())
            .put("limitBytes", cfg.storageLimitBytes)
            .put("night", s.night)
            .put("nightMode", cfg.night.name.lowercase())
            .put("resolution", s.videoResolution)
            .put("stillResolution", s.stillResolution)
            .put("wifiNetwork", net.wifi ?: JSONObject.NULL)
            .put("wifiActive", net.wifiActive)
            .put("cellularNetwork", net.cellular ?: JSONObject.NULL)
            .put("cellularActive", net.cellularActive)
            .put("version", org.empco.petcam.BuildConfig.VERSION_NAME)
            .put("clips", s.clipsRecorded)
            .put("photos", s.photosTaken)
            .put("photoOnlyIntervalSeconds", cfg.photoOnlyIntervalSeconds)
            .put("photoBurstIntervalSeconds", cfg.photoBurstIntervalSeconds)
            .put("photoOnlyPostMotionSeconds", cfg.photoOnlyPostMotionSeconds)
            .put("error", s.error ?: JSONObject.NULL)
            .put("ts", System.currentTimeMillis())
        liveLink.sendText(json.toString())
    }

    // ---------------------------------------------------------------- teardown

    private fun teardown() {
        periodicJob?.cancel()
        configJob?.cancel()
        liveLink.stop()
        try { recording?.stop() } catch (_: Exception) { }
        recording = null
        unbindCamera()
        analysisExecutor.shutdown()
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
