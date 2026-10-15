package org.empco.securitycam.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import org.empco.securitycam.settings.NightMode
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Low-light ("night vision") without emitting any light.
 *
 * Darkness is judged from the sensor's own exposure (exposure time × ISO) when the HAL reports it,
 * otherwise from the mean luma of the analysis stream, with hysteresis so it never flaps.
 * At night the camera may stretch exposure (lowest AE frame-rate floor), gets +1 EV exposure
 * compensation, high-quality noise reduction and the NIGHT scene mode where the HAL offers it.
 * Stills taken at night are additionally brightened in software (adaptive gamma).
 */
class NightVision(private val onChange: (Boolean) -> Unit) {

    @Volatile var active = false
        private set

    @Volatile private var exposureScore = -1.0   // ms × (ISO / 100); -1 = HAL doesn't report
    @Volatile private var lastResultAt = 0L
    @Volatile private var meanLuma = -1f
    private var darkSince = 0L
    private var brightSince = 0L
    private var mode = NightMode.AUTO
    private var camera: Camera? = null

    /** Attach to an Interop extender so we can read exposure from every frame. */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastResultAt < 500) return
            lastResultAt = now
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            if (exp <= 0 || iso <= 0) return
            exposureScore = exp / 1_000_000.0 * (iso / 100.0)
        }
    }

    fun onLuma(mean: Float) { meanLuma = mean }

    fun bind(cam: Camera, m: NightMode) {
        camera = cam
        mode = m
        darkSince = 0L; brightSince = 0L
        val want = when (m) { NightMode.ON -> true; NightMode.OFF -> false; NightMode.AUTO -> active }
        apply(want, force = true)
    }

    fun setMode(m: NightMode) {
        mode = m
        when (m) {
            NightMode.ON -> apply(true)
            NightMode.OFF -> apply(false)
            NightMode.AUTO -> Unit
        }
    }

    fun unbind() { camera = null }

    /** Called about once a second from the main thread. */
    fun evaluate() {
        if (mode != NightMode.AUTO || camera == null) return
        val now = SystemClock.elapsedRealtime()
        val exp = exposureScore
        val haveExp = exp >= 0 && now - lastResultAt < 5_000
        val dark: Boolean
        val bright: Boolean
        if (haveExp) {
            // Daylight indoors ≈ 10–60; dim room ≈ 250; night ≈ 1000+.
            dark = exp > DARK_EXPOSURE
            bright = exp < BRIGHT_EXPOSURE
        } else {
            val l = meanLuma
            if (l < 0) return
            dark = l < DARK_LUMA
            bright = l > BRIGHT_LUMA
        }
        darkSince = if (dark) (if (darkSince == 0L) now else darkSince) else 0L
        brightSince = if (bright) (if (brightSince == 0L) now else brightSince) else 0L
        if (!active && darkSince != 0L && now - darkSince > HOLD_MS) apply(true)
        else if (active && brightSince != 0L && now - brightSince > HOLD_MS) apply(false)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("UnsafeOptInUsageError")
    private fun apply(on: Boolean, force: Boolean = false) {
        if (on == active && !force) return
        val cam = camera
        val changed = on != active
        active = on
        if (cam != null) {
            try {
                val c2 = Camera2CameraControl.from(cam.cameraControl)
                if (on) {
                    val info = Camera2CameraInfo.from(cam.cameraInfo)
                    val b = CaptureRequestOptions.Builder()
                    nightFpsRange(info)?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    val nr = info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    if (nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true) {
                        b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    }
                    val scenes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                    if (scenes?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true) {
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
                    }
                    // Never any light: flash/torch stay off even in scene mode.
                    b.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    c2.setCaptureRequestOptions(b.build())
                } else {
                    c2.clearCaptureRequestOptions()
                }
                val ex = cam.cameraInfo.exposureState
                if (ex.isExposureCompensationSupported) {
                    val step = ex.exposureCompensationStep.toFloat()
                    val idx = if (on && step > 0f) (1f / step).roundToInt().coerceAtMost(ex.exposureCompensationRange.upper) else 0
                    cam.cameraControl.setExposureCompensationIndex(idx.coerceAtLeast(ex.exposureCompensationRange.lower))
                }
                Log.i(TAG, "night mode ${if (on) "ON" else "off"} (exposure=${"%.0f".format(exposureScore)}, luma=${"%.0f".format(meanLuma)})")
            } catch (e: Exception) {
                Log.w(TAG, "night mode apply failed", e)
            }
        }
        if (changed) onChange(on)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun nightFpsRange(info: Camera2CameraInfo): Range<Int>? {
        val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Lowest floor lets auto-exposure use long shutter times; among those prefer the highest ceiling.
        val best = ranges.filter { it.upper > 0 }
            .minWithOrNull(compareBy<Range<Int>>({ it.lower }, { -it.upper })) ?: return null
        return if (best.lower < best.upper) best else null
    }

    companion object {
        private const val TAG = "NightVision"
        private const val HOLD_MS = 15_000L
        private const val DARK_EXPOSURE = 400.0
        private const val BRIGHT_EXPOSURE = 150.0
        private const val DARK_LUMA = 45f
        private const val BRIGHT_LUMA = 115f

        /**
         * Brighten a dark still in place (adaptive gamma so the mean lands near mid-grey).
         * Returns the same bitmap when mutable, otherwise a mutable copy; no-op if already bright.
         */
        fun enhance(src: Bitmap): Bitmap {
            val mean = sampleLuma(src)
            if (mean <= 0f || mean >= 95f) return src
            val gamma = (ln(110.0 / 255.0) / ln(mean / 255.0)).coerceIn(0.4, 1.0)
            val lut = IntArray(256) { i -> (255.0 * (i / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255) }
            val b = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
            val w = b.width
            val row = IntArray(w)
            for (y in 0 until b.height) {
                b.getPixels(row, 0, w, 0, y, w, 1)
                for (i in 0 until w) {
                    val p = row[i]
                    row[i] = (p and -0x1000000) or (lut[(p shr 16) and 255] shl 16) or
                        (lut[(p shr 8) and 255] shl 8) or lut[p and 255]
                }
                b.setPixels(row, 0, w, 0, y, w, 1)
            }
            if (b !== src) src.recycle()
            return b
        }

        private fun sampleLuma(b: Bitmap): Float {
            val stepX = (b.width / 64).coerceAtLeast(1)
            val stepY = (b.height / 64).coerceAtLeast(1)
            var sum = 0L; var n = 0
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val p = b.getPixel(x, y)
                    sum += (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
                    n++; x += stepX
                }
                y += stepY
            }
            return if (n == 0) -1f else sum.toFloat() / n
        }
    }
}
