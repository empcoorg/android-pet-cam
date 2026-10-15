package org.empco.securitycam.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap block-difference motion detector working on the luma (Y) plane.
 * The frame is divided into a cols x rows grid; each block's mean luma is compared with the
 * previous frame. The returned score is the fraction of blocks whose mean changed by more than
 * [BLOCK_DIFF]. Lighting flicker moves a few blocks slightly; a person moves many blocks a lot.
 */
class MotionDetector(private val cols: Int = 24, private val rows: Int = 18) {
    private var prev: FloatArray? = null
    private val cur = FloatArray(cols * rows)

    @Volatile var lastScore: Float = 0f
        private set

    /** Mean luma (0..255) of the last frame, for night detection. */
    @Volatile var meanLuma: Float = -1f
        private set

    /** Bounding box of the changed blocks in the last frame, normalized [l, t, r, b]; null if none. */
    @Volatile var lastBox: FloatArray? = null
        private set

    fun reset() { prev = null; lastScore = 0f }

    fun analyze(y: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Float {
        val bw = width / cols
        val bh = height / rows
        if (bw < 2 || bh < 2) return 0f
        val step = max(1, min(bw, bh) / 6)
        val limit = y.limit()
        for (r in 0 until rows) {
            val y0 = r * bh
            for (c in 0 until cols) {
                val x0 = c * bw
                var sum = 0
                var n = 0
                var yy = y0
                while (yy < y0 + bh) {
                    val rowOff = yy * rowStride
                    var xx = x0
                    while (xx < x0 + bw) {
                        val idx = rowOff + xx * pixelStride
                        if (idx < limit) {
                            sum += y.get(idx).toInt() and 0xFF
                            n++
                        }
                        xx += step
                    }
                    yy += step
                }
                cur[r * cols + c] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
        val p = prev
        var changed = 0
        var minC = cols; var maxC = -1; var minR = rows; var maxR = -1
        var lumaSum = 0f
        for (v in cur) lumaSum += v
        meanLuma = lumaSum / cur.size
        if (p != null) {
            for (i in cur.indices) if (abs(cur[i] - p[i]) > BLOCK_DIFF) {
                changed++
                val c = i % cols; val r = i / cols
                if (c < minC) minC = c; if (c > maxC) maxC = c
                if (r < minR) minR = r; if (r > maxR) maxR = r
            }
        }
        lastBox = if (changed == 0) null else floatArrayOf(
            minC.toFloat() / cols, minR.toFloat() / rows, (maxC + 1).toFloat() / cols, (maxR + 1).toFloat() / rows,
        )
        prev = (p ?: FloatArray(cur.size)).also { cur.copyInto(it) }
        lastScore = if (p == null) 0f else changed.toFloat() / cur.size
        return lastScore
    }

    companion object {
        /** Mean-luma change (0..255) that marks a block as "changed". */
        const val BLOCK_DIFF = 12f

        /** Fraction of changed blocks needed to call it motion, for sensitivity 1..10. */
        fun thresholdFor(sensitivity: Int): Float {
            val s = sensitivity.coerceIn(1, 10)
            // s=1 -> ~25% of the frame must change; s=10 -> ~0.5% (about 2 blocks).
            return 0.25f * (1f - (s - 1) / 9f) + 0.005f
        }
    }
}
