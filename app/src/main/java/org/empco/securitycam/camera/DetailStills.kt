package org.empco.securitycam.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * "Most important details": after a motion clip is finalized, pull full-resolution frames from the
 * video at the moments of strongest motion, plus a close-up crop of the area that moved.
 * Video frames are at the clip's resolution (720p/1080p) — far more detail than the low-res
 * analysis stream that drives motion detection.
 */
object DetailStills {
    private const val TAG = "DetailStills"
    const val MAX_PEAKS = 3
    private const val MIN_GAP_MS = 3_000L

    /**
     * One motion peak inside a recording.
     * [box] is normalized [l, t, r, b] in analysis-buffer (sensor) coordinates; [analysisAspect] is
     * that buffer's width / height.
     */
    data class Peak(
        val offsetMs: Long,
        val wallTime: Long,
        val score: Float,
        val box: FloatArray?,
        val analysisAspect: Float,
        val night: Boolean,
    )

    /** Keeps the strongest peaks at least [MIN_GAP_MS] apart. Not thread-safe; use from one thread. */
    class Collector {
        private val peaks = ArrayList<Peak>()
        fun clear() = peaks.clear()
        fun add(p: Peak) {
            val near = peaks.indexOfFirst { kotlin.math.abs(it.offsetMs - p.offsetMs) < MIN_GAP_MS }
            if (near >= 0) {
                if (peaks[near].score < p.score) peaks[near] = p
                return
            }
            peaks.add(p)
            if (peaks.size > MAX_PEAKS * 4) trim(MAX_PEAKS * 2)
        }
        fun take(): List<Peak> {
            trim(MAX_PEAKS)
            return peaks.sortedBy { it.offsetMs }.also { peaks.clear() }
        }
        private fun trim(n: Int) {
            if (peaks.size <= n) return
            val keep = peaks.sortedByDescending { it.score }.take(n)
            peaks.clear(); peaks.addAll(keep)
        }
    }

    /** Output of one extracted peak: the upright full frame and an optional upright crop. */
    class Result(val peak: Peak, val frame: Bitmap, val crop: Bitmap?)

    /** Extract frames for [peaks] from [video]. Caller must recycle the returned bitmaps. */
    fun extract(video: File, peaks: List<Peak>, sink: (Result) -> Unit) {
        if (peaks.isEmpty() || !video.exists()) return
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            for (p in peaks) {
                val t = p.offsetMs.coerceIn(0, (durMs - 100).coerceAtLeast(0))
                var frame = try {
                    mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) { null } ?: continue

                // Normalise to the stored (sensor) orientation, then rotate everything upright at the end.
                val quarter = rot == 90 || rot == 270
                if (quarter && vw != vh && frame.width == vh && frame.height == vw) {
                    frame = rotate(frame, -rot)
                }
                val crop = p.box?.let { cropFor(frame, it, p.analysisAspect) }
                val uprightFrame = if (rot != 0) rotate(frame, rot) else frame
                val uprightCrop = crop?.let { if (rot != 0) rotate(it, rot) else it }
                sink(Result(p, uprightFrame, uprightCrop))
            }
        } catch (e: Exception) {
            Log.w(TAG, "detail extraction failed for ${video.name}", e)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "out of memory extracting ${video.name}")
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    }

    /** Crop the moving area (expanded, minimum 35% of the frame); null if it is most of the frame anyway. */
    private fun cropFor(frame: Bitmap, box: FloatArray, analysisAspect: Float): Bitmap? {
        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        // Both streams are centred crops of the same sensor area: map analysis coords into the video's.
        val va = fw / fh
        var l = box[0]; var t = box[1]; var r = box[2]; var b = box[3]
        if (analysisAspect > 0f && kotlin.math.abs(va - analysisAspect) > 0.02f) {
            if (va > analysisAspect) { // video is wider -> it shows less height
                val k = analysisAspect / va; val off = (1f - k) / 2f
                t = (t - off) / k; b = (b - off) / k
            } else {
                val k = va / analysisAspect; val off = (1f - k) / 2f
                l = (l - off) / k; r = (r - off) / k
            }
        }
        l = l.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f); t = t.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        if (r - l <= 0f || b - t <= 0f) return null
        if ((r - l) * (b - t) > 0.6f) return null
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val w = ((r - l) * 1.6f).coerceAtLeast(0.35f).coerceAtMost(1f)
        val h = ((b - t) * 1.6f).coerceAtLeast(0.35f).coerceAtMost(1f)
        val x0 = ((cx - w / 2f).coerceIn(0f, 1f - w) * fw).toInt()
        val y0 = ((cy - h / 2f).coerceIn(0f, 1f - h) * fh).toInt()
        val cw = (w * fw).toInt().coerceAtMost(frame.width - x0)
        val ch = (h * fh).toInt().coerceAtMost(frame.height - y0)
        if (cw < 64 || ch < 64) return null
        return Bitmap.createBitmap(frame, x0, y0, cw, ch)
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
