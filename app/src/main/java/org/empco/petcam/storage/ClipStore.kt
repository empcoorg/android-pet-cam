package org.empco.petcam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.WorkManager
import org.empco.petcam.settings.CaptureMode
import org.empco.petcam.settings.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipType { VIDEO, PHOTO }

data class Clip(
    val file: File,
    val type: ClipType,
    val startTime: Long,
    val sizeBytes: Long,
    val uploaded: Boolean,
) {
    val name: String get() = file.name
    val isVideo: Boolean get() = type == ClipType.VIDEO
    val mode: String
        get() = when {
            name.contains("_motion") -> "motion"
            name.contains("_continuous") -> "continuous"
            name.contains("_photo") -> "photo"
            else -> ""
        }
}

/**
 * Flat-file clip repository. Clips live in app-specific external storage (no runtime
 * storage permission required) and are named by their start time so lexical order == time order.
 * An empty `<name>.uploaded` marker next to a file records a successful cloud upload.
 */
class ClipStore(context: Context) {
    private val appContext = context.applicationContext
    private val removableRoot: File? = removableDir(appContext)
    /** True when footage is going to a removable SD card. */
    val onSdCard: Boolean = Settings.get(appContext).current.useSdCard && removableRoot != null
    private val root: File = (if (onSdCard) removableRoot else null)
        ?: appContext.getExternalFilesDir(null) ?: appContext.filesDir
    val locationLabel: String get() = if (onSdCard) "SD card" else "phone storage"
    val videosDir: File = File(root, "videos").apply { mkdirs() }
    val photosDir: File = File(root, "photos").apply { mkdirs() }

    private val nameFmt get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun newVideoFile(mode: CaptureMode): File =
        uniqueFile(videosDir, "${nameFmt.format(Date())}_${mode.name.lowercase()}", "mp4")

    fun newPhotoFile(): File =
        uniqueFile(photosDir, "${nameFmt.format(Date())}_photo", "jpg")

    /** Photo named for the moment it shows (e.g. a frame cut from a clip); [kind] like "detail". */
    fun newPhotoFileAt(time: Long, kind: String): File =
        uniqueFile(photosDir, "${nameFmt.format(Date(time))}_$kind", "jpg")

    /** Capture time encoded in a file name (local time), or the file's mtime. */
    fun capturedAt(f: File): Long = try {
        nameFmt.parse(f.name.take(19))?.time ?: f.lastModified()
    } catch (_: Exception) { f.lastModified() }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 1
        while (f.exists()) f = File(dir, "${base}_${i++}.$ext")
        return f
    }

    fun markerFor(file: File): File = File(file.parentFile, file.name + UPLOADED_SUFFIX)

    private fun allFiles(): List<File> {
        val out = ArrayList<File>()
        videosDir.listFiles()?.forEach { out.add(it) }
        photosDir.listFiles()?.forEach { out.add(it) }
        return out
    }

    fun isMedia(f: File) = f.isFile && !f.name.endsWith(UPLOADED_SUFFIX) &&
        (f.extension.equals("mp4", true) || f.extension.equals("jpg", true))

    /** Key used in the cloud bucket. */
    fun remoteKey(file: File): String =
        (if (file.extension.equals("mp4", true)) "videos/" else "photos/") + file.name

    fun list(): List<Clip> {
        return allFiles().filter { isMedia(it) }
            .map { toClip(it, if (it.extension.equals("mp4", true)) ClipType.VIDEO else ClipType.PHOTO) }
            .sortedByDescending { it.startTime }
    }

    fun pendingUploads(): List<File> =
        allFiles().filter { isMedia(it) && !markerFor(it).exists() }.sortedBy { it.lastModified() }

    private fun toClip(f: File, type: ClipType): Clip {
        val start = try {
            nameFmt.parse(f.name.take(19))?.time ?: f.lastModified()
        } catch (_: Exception) { f.lastModified() }
        return Clip(f, type, start, f.length(), markerFor(f).exists())
    }

    fun usedBytes(): Long =
        allFiles().filter { it.isFile }.sumOf { it.length() }

    fun delete(clip: Clip) = delete(clip.file)

    fun delete(file: File) {
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork("upload:" + file.name)
        } catch (_: Exception) { }
        markerFor(file).delete()
        file.delete()
    }

    fun freeBytes(): Long = try { StatFs(root.path).availableBytes } catch (_: Exception) { 0L }
    fun totalBytes(): Long = try { StatFs(root.path).totalBytes } catch (_: Exception) { 0L }

    /**
     * Delete oldest media while free space on the storage is below [minFreePercent] of its
     * capacity, or (if [limitBytes] > 0) while our own footage exceeds that cap.
     * [exclude] is the file currently being written and is never deleted.
     */
    fun enforceLimit(limitBytes: Long, minFreePercent: Int, exclude: File?): Int {
        // Already-uploaded footage goes first (it is safe in the cloud), then oldest.
        val files = allFiles().filter { isMedia(it) && it != exclude }
            .sortedWith(compareBy<File>({ !markerFor(it).exists() }, { it.lastModified() }))
        val total = totalBytes()
        val minFree = total * minFreePercent.coerceIn(0, 90) / 100
        var used = usedBytes()
        var free = freeBytes()
        var removed = 0
        for (f in files) {
            val overCap = limitBytes > 0 && used > limitBytes
            val lowSpace = total > 0 && free < minFree
            if (!overCap && !lowSpace) break
            val len = f.length()
            Log.i(TAG, "Retention: deleting ${f.name} (${len / 1024} KB) free=${free / 1048576}MB used=${used / 1048576}MB")
            delete(f)
            used -= len
            free += len
            removed++
        }
        return removed
    }

    /** Copies the clip into the public Movies/PetCam or Pictures/PetCam collection. */
    fun saveToGallery(clip: Clip): Boolean {
        val resolver = appContext.contentResolver
        val isVideo = clip.isVideo
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, clip.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/PetCam" else "Pictures/PetCam")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> clip.file.inputStream().use { it.copyTo(out) } }
                ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "saveToGallery failed", e)
            resolver.delete(uri, null, null)
            false
        }
    }

    /** FileProvider URI; files on a secondary volume are staged through the cache first. */
    fun shareUri(clip: Clip): Uri {
        val authority = appContext.packageName + ".files"
        return try {
            FileProvider.getUriForFile(appContext, authority, clip.file)
        } catch (_: IllegalArgumentException) {
            val staged = File(File(appContext.cacheDir, "share").apply { mkdirs() }, clip.name)
            clip.file.copyTo(staged, overwrite = true)
            FileProvider.getUriForFile(appContext, authority, staged)
        }
    }

    companion object {
        private const val TAG = "ClipStore"
        const val UPLOADED_SUFFIX = ".uploaded"

        /** App-specific directory on a removable SD card, if one is mounted. */
        fun removableDir(context: Context): File? =
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull { d ->
                try {
                    Environment.isExternalStorageRemovable(d) &&
                        Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED
                } catch (_: Exception) { false }
            }

        fun sdCardAvailable(context: Context): Boolean = removableDir(context) != null
    }
}
