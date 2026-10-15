package org.empco.petcam.camera

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.OverlayEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** "UTC-6" / "UTC+5:30" for the device's current offset — timestamps are the camera's local time,
 *  not necessarily the viewer's, so every displayed stamp says which zone it's in. */
fun utcOffsetLabel(atMillis: Long = System.currentTimeMillis()): String {
    val offsetMin = TimeZone.getDefault().getOffset(atMillis) / 60_000
    val sign = if (offsetMin >= 0) "+" else "-"
    val a = abs(offsetMin)
    val h = a / 60
    val m = a % 60
    return "UTC$sign$h" + if (m != 0) ":%02d".format(m) else ""
}

/**
 * CameraX [OverlayEffect] that burns the wall-clock date/time (and an optional label) into
 * every video frame. The effect targets VIDEO_CAPTURE only, so the live preview and stills are
 * untouched and the stamp is part of the encoded video, not metadata.
 */
class TimestampOverlay(@Volatile var label: String) {
    private val thread = HandlerThread("petcam-overlay").apply { start() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dowFmt = SimpleDateFormat("EEE", Locale.US)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        0,
        Handler(thread.looper),
    ) { t -> Log.e(TAG, "Overlay effect error", t) }

    init {
        effect.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Draw in "display" coordinates so the stamp is upright regardless of how the
            // buffer is rotated before the encoder sees it.
            val bw = frame.size.width
            val bh = frame.size.height
            val rot = frame.rotationDegrees
            val dw = if (rot == 90 || rot == 270) bh else bw
            val dh = if (rot == 90 || rot == 270) bw else bh
            c.save()
            when (rot) {
                90 -> { c.translate(0f, bh.toFloat()); c.rotate(-90f) }
                180 -> { c.translate(bw.toFloat(), bh.toFloat()); c.rotate(180f) }
                270 -> { c.translate(bw.toFloat(), 0f); c.rotate(90f) }
            }

            val size = (minOf(dw, dh) * 0.017f).coerceAtLeast(7f)
            text.textSize = size
            outline.textSize = size
            outline.strokeWidth = size * 0.11f
            val pad = size * 0.4f
            val l = label
            val now = Date()
            val base = fmt.format(now) + " " + dowFmt.format(now).uppercase(Locale.US) + " " + utcOffsetLabel(now.time)
            val s = if (l.isBlank()) base else "$base  $l"
            val x = pad * 2
            val y = dh - pad * 2
            c.drawText(s, x, y, outline)
            c.drawText(s, x, y, text)
            c.restore()
            true
        }
    }

    fun close() {
        try { effect.close() } catch (_: Exception) { }
        thread.quitSafely()
    }

    companion object { private const val TAG = "TimestampOverlay" }
}
