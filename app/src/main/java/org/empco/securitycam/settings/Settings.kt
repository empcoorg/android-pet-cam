package org.empco.securitycam.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.empco.securitycam.BuildConfig

/** Trigger: MOTION-gated capture, or CONTINUOUS regardless of motion. Applies to both [MediaType]s. */
enum class CaptureMode { MOTION, CONTINUOUS }
/** What gets captured: full video, or full-res stills only (no video/analysis streams normally
 *  needed — for low-end hardware). Orthogonal to [CaptureMode]. */
enum class MediaType { VIDEO, PHOTO_ONLY }
enum class Lens { BACK, FRONT }
enum class VideoQuality { SD, HD, FHD }

/**
 * Camera capability profile. Many budget phones can only run 2 high-res streams at once, so each
 * profile picks which trio of {video, burned-in timestamp, live-view+motion} to run:
 *  - BALANCED:   all features at 640x480 (timestamp burned in, live view, motion).
 *  - EVIDENCE:   720p + burned-in timestamp, but NO live view / motion.
 *  - HIRES_LIVE: 720p/1080p + live view + motion, timestamp drawn on live/stills + at playback
 *                (not burned into the recorded video pixels).
 *  - MAX:        highest resolution the camera actually delivers (tries 1080p → 720p, full-res
 *                stills when a third stream fits), live view + motion, soft timestamp, and
 *                full-resolution "detail" stills cut from the video at motion peaks.
 */
enum class Profile { BALANCED, EVIDENCE, HIRES_LIVE, MAX }

/** Low-light handling: AUTO switches on when the scene gets dark (no light is ever emitted). */
enum class NightMode { AUTO, ON, OFF }

/** How the phone is physically mounted; the video is rotated so it plays upright. */
enum class Orientation { PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, UPSIDE_DOWN }

/** Immutable snapshot of every user-tunable setting. */
data class Config(
    val mode: CaptureMode = CaptureMode.MOTION,
    val mediaType: MediaType = MediaType.VIDEO,
    val lens: Lens = Lens.BACK,
    val quality: VideoQuality = VideoQuality.FHD,
    val orientation: Orientation = Orientation.PORTRAIT,
    /** Record the full 4:3 sensor frame instead of a 16:9 crop (more vertical coverage). */
    val wideAspect: Boolean = true,
    val sound: Boolean = true,
    /** 1 (least sensitive) .. 10 (most sensitive). */
    val sensitivity: Int = 5,
    /** Keep recording this long after the last detected motion. */
    val postMotionSeconds: Int = 15,
    /** Still photo cadence while motion is active. */
    val photoIntervalSeconds: Int = 10,
    /** Full-res photo cadence for PHOTO_ONLY media: the CONTINUOUS trigger's fixed cadence, and
     *  the MOTION trigger's slower post-motion-tail cadence. */
    val photoOnlyIntervalSeconds: Int = 10,
    /** PHOTO_ONLY + MOTION: burst cadence while motion is actively detected. */
    val photoBurstIntervalSeconds: Int = 5,
    /** PHOTO_ONLY + MOTION: keep taking slower (photoOnlyIntervalSeconds-paced) photos for this
     *  long after the last detected motion, then stop until motion resumes. */
    val photoOnlyPostMotionSeconds: Int = 60,
    /** Clip length in continuous mode. */
    val clipMinutes: Int = 5,
    /** Optional fixed local cap in MB (0 = off). Retention is primarily free-space based. */
    val storageLimitMb: Int = 0,
    /** Oldest footage is deleted only when free space on the storage falls below this percentage. */
    val minFreePercent: Int = 50,
    /** Store footage on a removable SD card when one is present. */
    val useSdCard: Boolean = true,
    /** Start the camera service automatically whenever the app is opened. */
    val autoStart: Boolean = true,
    val profile: Profile = Profile.MAX,
    /** Burn the timestamp into the video's own pixels. Independent of profile: only BALANCED,
     *  EVIDENCE and HIRES_LIVE can actually honor it (MAX's binding path has no compositing stage
     *  for it) — Settings greys the toggle out on MAX rather than silently ignoring it. */
    val burnTimestamp: Boolean = true,
    val night: NightMode = NightMode.AUTO,
    /** After each motion clip, save full-resolution frames + crops of the moving area at motion peaks. */
    val detailStills: Boolean = true,
    /** Short label stamped next to the timestamp. */
    val cameraLabel: String = "Camera",
    val cloudUrl: String = BuildConfig.CLOUD_URL,
    val deviceToken: String = BuildConfig.DEVICE_TOKEN,
    val uploadEnabled: Boolean = BuildConfig.DEVICE_TOKEN.isNotBlank(),
    /** Off: upload over mobile data (SIM preferred) or Wi-Fi, whichever is available. */
    val uploadWifiOnly: Boolean = false,
    val liveEnabled: Boolean = BuildConfig.DEVICE_TOKEN.isNotBlank(),
    val liveFps: Int = 15,
) {
    val storageLimitBytes: Long get() = storageLimitMb.toLong() * 1024L * 1024L
    val cloudConfigured: Boolean get() = cloudUrl.isNotBlank() && deviceToken.isNotBlank()
    /** No live/motion analysis stream (evidence mode only). */
    val noAnalysis: Boolean get() = profile == Profile.EVIDENCE
    /** High-resolution (2-stream) profiles. */
    val highRes: Boolean get() = profile == Profile.EVIDENCE || profile == Profile.HIRES_LIVE || profile == Profile.MAX
    /** Only MAX's binding path has no compositing stage to actually burn the timestamp in. */
    val canBurnTimestamp: Boolean get() = profile != Profile.MAX
    /** Draw the timestamp onto live frames and stills in software: whenever it isn't burned into
     *  video, including PHOTO_ONLY media, which has no video stream for a burn to ever apply to. */
    val softTimestamp: Boolean get() = mediaType == MediaType.PHOTO_ONLY || !burnTimestamp || !canBurnTimestamp
}

class Settings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("securitycam", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(migrate(read()))
    val config: StateFlow<Config> = _config
    val current: Config get() = _config.value

    fun update(block: (Config) -> Config) {
        val next = block(_config.value)
        write(next)
        _config.value = next
    }

    private fun read(): Config {
        val d = Config()
        return Config(
            mode = enumOr(prefs.getString("mode", null), d.mode),
            mediaType = enumOr(prefs.getString("mediaType", null), d.mediaType),
            lens = enumOr(prefs.getString("lens", null), d.lens),
            quality = enumOr(prefs.getString("quality", null), d.quality),
            orientation = enumOr(prefs.getString("orientation", null), d.orientation),
            wideAspect = prefs.getBoolean("wideAspect", d.wideAspect),
            sound = prefs.getBoolean("sound", d.sound),
            sensitivity = prefs.getInt("sensitivity", d.sensitivity),
            postMotionSeconds = prefs.getInt("postMotionSeconds", d.postMotionSeconds),
            photoIntervalSeconds = prefs.getInt("photoIntervalSeconds", d.photoIntervalSeconds),
            photoOnlyIntervalSeconds = prefs.getInt("photoOnlyIntervalSeconds", d.photoOnlyIntervalSeconds),
            photoBurstIntervalSeconds = prefs.getInt("photoBurstIntervalSeconds", d.photoBurstIntervalSeconds),
            photoOnlyPostMotionSeconds = prefs.getInt("photoOnlyPostMotionSeconds", d.photoOnlyPostMotionSeconds),
            clipMinutes = prefs.getInt("clipMinutes", d.clipMinutes),
            storageLimitMb = prefs.getInt("storageLimitMb", d.storageLimitMb),
            minFreePercent = prefs.getInt("minFreePercent", d.minFreePercent),
            useSdCard = prefs.getBoolean("useSdCard", d.useSdCard),
            autoStart = prefs.getBoolean("autoStart", d.autoStart),
            profile = enumOr(prefs.getString("profile", null), d.profile),
            burnTimestamp = prefs.getBoolean("burnTimestamp", d.burnTimestamp),
            night = enumOr(prefs.getString("night", null), d.night),
            detailStills = prefs.getBoolean("detailStills", d.detailStills),
            cameraLabel = prefs.getString("cameraLabel", d.cameraLabel) ?: d.cameraLabel,
            cloudUrl = (prefs.getString("cloudUrl", null) ?: "").ifBlank { d.cloudUrl },
            deviceToken = (prefs.getString("deviceToken", null) ?: "").ifBlank { d.deviceToken },
            uploadEnabled = prefs.getBoolean("uploadEnabled", d.uploadEnabled),
            uploadWifiOnly = prefs.getBoolean("uploadWifiOnly", d.uploadWifiOnly),
            liveEnabled = prefs.getBoolean("liveEnabled", d.liveEnabled),
            liveFps = prefs.getInt("liveFps", d.liveFps),
        )
    }

    private fun write(c: Config) {
        prefs.edit {
            putString("mode", c.mode.name)
            putString("mediaType", c.mediaType.name)
            putString("lens", c.lens.name)
            putString("quality", c.quality.name)
            putString("orientation", c.orientation.name)
            putBoolean("wideAspect", c.wideAspect)
            putBoolean("sound", c.sound)
            putInt("sensitivity", c.sensitivity)
            putInt("postMotionSeconds", c.postMotionSeconds)
            putInt("photoIntervalSeconds", c.photoIntervalSeconds)
            putInt("photoOnlyIntervalSeconds", c.photoOnlyIntervalSeconds)
            putInt("photoBurstIntervalSeconds", c.photoBurstIntervalSeconds)
            putInt("photoOnlyPostMotionSeconds", c.photoOnlyPostMotionSeconds)
            putInt("clipMinutes", c.clipMinutes)
            putInt("storageLimitMb", c.storageLimitMb)
            putInt("minFreePercent", c.minFreePercent)
            putBoolean("useSdCard", c.useSdCard)
            putBoolean("autoStart", c.autoStart)
            putString("profile", c.profile.name)
            putBoolean("burnTimestamp", c.burnTimestamp)
            putString("night", c.night.name)
            putBoolean("detailStills", c.detailStills)
            putString("cameraLabel", c.cameraLabel)
            putString("cloudUrl", c.cloudUrl)
            putString("deviceToken", c.deviceToken)
            putBoolean("uploadEnabled", c.uploadEnabled)
            putBoolean("uploadWifiOnly", c.uploadWifiOnly)
            putBoolean("liveEnabled", c.liveEnabled)
            putInt("liveFps", c.liveFps)
        }
    }

    /**
     * One-time upgrades of an existing install. Schema 2 moves the phone to the max-resolution
     * profile with night mode and detail stills, and turns on cloud features when this build
     * carries keys. Schema 3 splits burnTimestamp out of profile into its own stored field —
     * migrating devices get whatever their current profile already implied, so this is a no-op
     * change in effect, not just in storage. Anything the user changes afterwards is kept.
     */
    private fun migrate(c: Config): Config {
        val schema = prefs.getInt("schema", 1)
        if (schema >= SCHEMA) return c
        var n = c
        if (schema < 2) {
            n = n.copy(
                profile = Profile.MAX, quality = VideoQuality.FHD, wideAspect = true,
                night = NightMode.AUTO, detailStills = true, uploadWifiOnly = false, autoStart = true,
            )
            if (BuildConfig.DEVICE_TOKEN.isNotBlank()) {
                n = n.copy(
                    cloudUrl = n.cloudUrl.ifBlank { BuildConfig.CLOUD_URL },
                    uploadEnabled = true, liveEnabled = true,
                )
            }
        }
        if (schema < 3) {
            n = n.copy(burnTimestamp = n.profile == Profile.BALANCED || n.profile == Profile.EVIDENCE)
        }
        if (schema < 4) {
            // CaptureMode briefly had a third value, PHOTO_ONLY, before MediaType split it out as
            // its own orthogonal dimension. A device saved under that scheme reads back as MOTION
            // here (the raw string no longer matches the enum) — recover the real intent from the
            // stored string and land on PHOTO_ONLY + MOTION (its new default trigger).
            if (prefs.getString("mode", null) == "PHOTO_ONLY") {
                n = n.copy(mediaType = MediaType.PHOTO_ONLY, mode = CaptureMode.MOTION)
            }
        }
        write(n)
        prefs.edit { putInt("schema", SCHEMA) }
        return n
    }

    /** The upload/live token was rejected: fall back to the token built into this APK. */
    fun adoptBuiltInToken(): Boolean {
        val baked = BuildConfig.DEVICE_TOKEN
        if (baked.isBlank() || current.deviceToken == baked) return false
        update { it.copy(deviceToken = baked, cloudUrl = it.cloudUrl.ifBlank { BuildConfig.CLOUD_URL }) }
        return true
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        private const val SCHEMA = 4
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext).also { instance = it }
        }
    }
}
