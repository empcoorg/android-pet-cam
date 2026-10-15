package org.empco.securitycam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object YuvUtil {
    private val nv21Cache = ThreadLocal<ByteArray>()

    /** Converts a YUV_420_888 ImageProxy to an NV21 byte array (handles any pixel/row stride). */
    fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val total = ySize + ySize / 2
        var out = nv21Cache.get()
        if (out == null || out.size != total) {
            out = ByteArray(total)
            nv21Cache.set(out)
        }
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        yBuf.rewind()
        if (yPlane.pixelStride == 1 && yPlane.rowStride == w) {
            yBuf.get(out, 0, ySize)
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(out, row * w, w)
            }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val cw = w / 2
        val ch = h / 2
        var off = ySize
        for (row in 0 until ch) {
            val vRow = row * vPlane.rowStride
            val uRow = row * uPlane.rowStride
            for (col in 0 until cw) {
                out[off++] = vBuf.get(vRow + col * vPlane.pixelStride)
                out[off++] = uBuf.get(uRow + col * uPlane.pixelStride)
            }
        }
        return out
    }

    /** JPEG bytes in sensor orientation (not rotated). */
    fun toJpeg(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = toNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val bos = ByteArrayOutputStream(image.width * image.height / 4)
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, bos)
        return bos.toByteArray()
    }

    /** Upright bitmap (rotation metadata applied). */
    fun toBitmap(image: ImageProxy, quality: Int = 75): Bitmap? {
        val jpeg = toJpeg(image, quality)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun bitmapToJpeg(bmp: Bitmap, quality: Int, maxLongEdge: Int = 0): ByteArray {
        var b = bmp
        if (maxLongEdge > 0) {
            val long = maxOf(b.width, b.height)
            if (long > maxLongEdge) {
                val s = maxLongEdge.toFloat() / long
                b = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
            }
        }
        val bos = ByteArrayOutputStream(64 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (b !== bmp) b.recycle()
        return bos.toByteArray()
    }
}
