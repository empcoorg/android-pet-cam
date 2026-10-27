package org.empco.petcam.storage

import java.io.File

/**
 * The file logic behind [ClipStore]: which entries are footage, which are already uploaded, and
 * what retention removes. Plain Kotlin with no Android dependencies, so it is unit-tested against
 * ordinary directories (see FootageTest).
 *
 * Cost matters here: a phone can hold tens of thousands of files, and this runs on every capture.
 * Every answer comes from one directory listing per folder; nothing stats a file inside a sort.
 */
class Footage(private val dirs: List<File>) {

    /** Media files plus the name of every entry beside them (markers are found by name, not by stat). */
    class Scan(val media: List<File>, private val names: Set<String>) {
        fun uploaded(f: File): Boolean = f.name + UPLOADED_SUFFIX in names
    }

    fun scan(): Scan {
        val media = ArrayList<File>()
        val names = HashSet<String>()
        for (dir in dirs) {
            dir.listFiles()?.forEach { f ->
                names.add(f.name)
                if (isMediaName(f.name)) media.add(f)
            }
        }
        return Scan(media, names)
    }

    /** Not yet uploaded, oldest first: names start with the capture time, so name order is time order. */
    fun pendingUploads(): List<File> {
        val s = scan()
        return s.media.filter { !s.uploaded(it) }.sortedBy { it.name }
    }

    /** Bytes of footage (markers and temporary files excluded). A full listing: keep off the main thread. */
    fun usedBytes(): Long = scan().media.sumOf { it.length() }

    /**
     * Delete footage, oldest first and already-uploaded first (it is safe elsewhere), while free space
     * is below [minFreePercent] of [totalBytes] or, with [limitBytes] > 0, while footage exceeds that cap.
     * [exclude] (the file being written) is never deleted; [delete] removes one file and its marker.
     *
     * Returns the footage total after the pass, or -1 when there was nothing to check: with no cap and
     * enough free space (the usual case) it answers from the two numbers alone, without listing files.
     */
    fun enforceLimit(
        limitBytes: Long, minFreePercent: Int, exclude: File?,
        totalBytes: Long, freeBytes: Long, delete: (File) -> Unit,
    ): Long {
        val minFree = totalBytes * minFreePercent.coerceIn(0, 90) / 100
        var free = freeBytes
        if (limitBytes <= 0 && !(totalBytes > 0 && free < minFree)) return -1

        val s = scan()
        val entries = s.media.map { Entry(it, s.uploaded(it), it.length()) }
        var used = entries.sumOf { it.size }
        val victims = entries.filter { it.file != exclude }
            .sortedWith(compareBy<Entry>({ !it.uploaded }, { it.file.name }))
        for (v in victims) {
            val overCap = limitBytes > 0 && used > limitBytes
            val lowSpace = totalBytes > 0 && free < minFree
            if (!overCap && !lowSpace) break
            delete(v.file)
            used -= v.size
            free += v.size
        }
        return used
    }

    private class Entry(val file: File, val uploaded: Boolean, val size: Long)

    companion object {
        /** An empty `<name>.uploaded` file next to a clip records a successful cloud upload. */
        const val UPLOADED_SUFFIX = ".uploaded"

        fun isMediaName(name: String): Boolean = !name.endsWith(UPLOADED_SUFFIX) &&
            (name.endsWith(".mp4", true) || name.endsWith(".jpg", true))
    }
}
