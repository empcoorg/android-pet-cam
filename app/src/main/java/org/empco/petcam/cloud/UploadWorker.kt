package org.empco.petcam.cloud

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.empco.petcam.settings.Settings
import org.empco.petcam.storage.ClipStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Uploads one clip/photo to the Cloudflare Worker. Small files use a single PUT; large files use
 * R2 multipart upload so no single request exceeds the Workers request-body limit.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure()

        val cfg = Settings.get(applicationContext).current
        if (!cfg.uploadEnabled || !cfg.cloudConfigured) return@withContext Result.failure()
        val store = ClipStore(applicationContext)
        if (store.markerFor(file).exists()) return@withContext Result.success()

        val base = Http.normalizeBase(cfg.cloudUrl)
        val key = store.remoteKey(file)
        val mime = (if (file.extension.equals("mp4", true)) "video/mp4" else "image/jpeg").toMediaType()
        val auth = "Bearer ${cfg.deviceToken}"
        capturedAt = store.capturedAt(file)

        try {
            val ok = if (file.length() <= SINGLE_PUT_MAX) {
                simplePut(base, key, file, mime, auth)
            } else {
                multipart(base, key, file, mime, auth)
            }
            if (ok == Outcome.PERMANENT && lastCode == 401 && Settings.get(applicationContext).adoptBuiltInToken()) {
                Log.w(TAG, "token rejected; switched to the built-in token, retrying")
                return@withContext Result.retry()
            }
            when (ok) {
                Outcome.OK -> { store.markerFor(file).createNewFile(); Log.i(TAG, "uploaded $key"); Result.success() }
                Outcome.PERMANENT -> { Log.w(TAG, "upload rejected for $key"); Result.failure() }
                Outcome.RETRY -> Result.retry()
            }
        } catch (e: IOException) {
            Log.w(TAG, "upload IO error for $key: ${e.message}")
            Result.retry()
        }
    }

    private enum class Outcome { OK, RETRY, PERMANENT }

    private var lastCode = 0
    private var capturedAt = 0L

    private fun classify(code: Int): Outcome = when {
        else -> { lastCode = code; classifyCode(code) }
    }

    private fun classifyCode(code: Int): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 401 || code == 403 || code == 400 || code == 413 -> Outcome.PERMANENT
        else -> Outcome.RETRY
    }

    private fun simplePut(base: String, key: String, file: File, mime: MediaType, auth: String): Outcome {
        val req = Request.Builder()
            .url("$base/api/device/upload/$key")
            .header("Authorization", auth)
            .header("X-Captured-At", capturedAt.toString())
            .put(file.asRequestBody(mime))
            .build()
        Http.client.newCall(req).execute().use { return classify(it.code) }
    }

    private fun multipart(base: String, key: String, file: File, mime: MediaType, auth: String): Outcome {
        val json = "application/json".toMediaType()
        // 1. create
        val createReq = Request.Builder()
            .url("$base/api/device/mpu/create?key=$key&contentType=${mime}&capturedAt=$capturedAt")
            .header("Authorization", auth)
            .post("".toRequestBody(null))
            .build()
        val uploadId = Http.client.newCall(createReq).execute().use { r ->
            if (!r.isSuccessful) return classify(r.code)
            JSONObject(r.body?.string() ?: "{}").optString("uploadId").ifBlank { return Outcome.RETRY }
        }
        // 2. parts
        val parts = JSONArray()
        val total = file.length()
        var offset = 0L
        var partNumber = 1
        while (offset < total) {
            val len = minOf(PART_SIZE, total - offset)
            val body = RangeBody(file, offset, len, mime)
            val req = Request.Builder()
                .url("$base/api/device/mpu/part?key=$key&uploadId=$uploadId&part=$partNumber")
                .header("Authorization", auth)
                .put(body)
                .build()
            val etag = Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { abort(base, key, uploadId, auth); return classify(r.code) }
                JSONObject(r.body?.string() ?: "{}").optString("etag")
            }
            if (etag.isBlank()) { abort(base, key, uploadId, auth); return Outcome.RETRY }
            parts.put(JSONObject().put("partNumber", partNumber).put("etag", etag))
            offset += len
            partNumber++
            if (isStopped) { abort(base, key, uploadId, auth); return Outcome.RETRY }
        }
        // 3. complete
        val completeReq = Request.Builder()
            .url("$base/api/device/mpu/complete?key=$key&uploadId=$uploadId")
            .header("Authorization", auth)
            .post(JSONObject().put("parts", parts).toString().toRequestBody(json))
            .build()
        Http.client.newCall(completeReq).execute().use { return classify(it.code) }
    }

    private fun abort(base: String, key: String, uploadId: String, auth: String) {
        try {
            Http.client.newCall(
                Request.Builder()
                    .url("$base/api/device/mpu/abort?key=$key&uploadId=$uploadId")
                    .header("Authorization", auth)
                    .post("".toRequestBody(null)).build()
            ).execute().close()
        } catch (_: IOException) { }
    }

    /** Streams a byte range of a file as a request body with a known length. */
    private class RangeBody(
        private val file: File, private val offset: Long, private val length: Long, private val mime: MediaType,
    ) : RequestBody() {
        override fun contentType(): MediaType = mime
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val input = object : java.io.InputStream() {
                    var remaining = length
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        val b = raf.read(); if (b >= 0) remaining--; return b
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
                        if (n > 0) remaining -= n
                        return n
                    }
                }
                sink.writeAll(input.source())
            }
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val KEY_PATH = "path"
        private const val SINGLE_PUT_MAX = 64L * 1024 * 1024
        private const val PART_SIZE = 32L * 1024 * 1024 // R2 requires >= 5 MiB and equal-sized parts

        fun enqueue(context: Context, file: File) {
            val cfg = Settings.get(context).current
            if (!cfg.uploadEnabled || !cfg.cloudConfigured) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (cfg.uploadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_PATH to file.absolutePath))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("upload:" + file.name, ExistingWorkPolicy.KEEP, req)
        }

        /** Re-queue anything that was recorded but never confirmed uploaded. */
        fun enqueuePending(context: Context) {
            val cfg = Settings.get(context).current
            if (!cfg.uploadEnabled || !cfg.cloudConfigured) return
            ClipStore(context).pendingUploads().forEach { enqueue(context, it) }
        }
    }
}
