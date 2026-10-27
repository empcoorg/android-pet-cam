package org.empco.petcam.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FootageTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var videos: File
    private lateinit var photos: File

    private fun setup(): Footage {
        videos = tmp.newFolder("videos")
        photos = tmp.newFolder("photos")
        return Footage(listOf(videos, photos))
    }

    private fun media(dir: File, name: String, bytes: Int, uploaded: Boolean = false): File {
        val f = File(dir, name).apply { writeBytes(ByteArray(bytes)) }
        if (uploaded) File(dir, name + Footage.UPLOADED_SUFFIX).createNewFile()
        return f
    }

    @Test fun pendingUploads_areUnmarkedMediaInCaptureOrder() {
        val fx = setup()
        val late = media(photos, "2026-10-02_09-00-00_photo.jpg", 10)
        val early = media(videos, "2026-10-01_08-00-00_motion.mp4", 10)
        media(photos, "2026-10-01_12-00-00_photo.jpg", 10, uploaded = true)
        File(photos, "2026-10-03_00-00-00_photo.jpg.tmp").createNewFile() // in-progress, not media
        // Modification times deliberately contradict the names: order must come from the names.
        early.setLastModified(2_000_000_000_000L); late.setLastModified(1_000_000_000_000L)

        assertEquals(listOf(early, late), fx.pendingUploads())
    }

    @Test fun usedBytes_countsOnlyMedia() {
        val fx = setup()
        media(videos, "a.mp4", 300, uploaded = true)
        media(photos, "b.jpg", 200)
        File(photos, "c.jpg.tmp").writeBytes(ByteArray(999))
        assertEquals(500L, fx.usedBytes())
    }

    @Test fun enforceLimit_fastPathWhenNoCapAndSpaceIsFine() {
        val fx = setup()
        media(photos, "a.jpg", 100)
        val deleted = ArrayList<File>()
        val used = fx.enforceLimit(0, 50, null, totalBytes = 1000, freeBytes = 600, delete = deleted::add)
        assertEquals(-1L, used)
        assertTrue(deleted.isEmpty())
    }

    @Test fun enforceLimit_lowSpace_deletesUploadedFirstThenOldest_untilFreeEnough() {
        val fx = setup()
        val upOld = media(videos, "2026-10-01_00-00-00_motion.mp4", 100, uploaded = true)
        val upNew = media(photos, "2026-10-04_00-00-00_photo.jpg", 100, uploaded = true)
        val pendOld = media(photos, "2026-10-02_00-00-00_photo.jpg", 100)
        val pendNew = media(photos, "2026-10-03_00-00-00_photo.jpg", 100)
        val deleted = ArrayList<File>()
        // 50% of 1000 = 500 must be free; 300 free now, so 200 bytes must go.
        val used = fx.enforceLimit(0, 50, null, totalBytes = 1000, freeBytes = 300) { deleted.add(it); it.delete() }

        assertEquals(listOf(upOld, upNew), deleted)
        assertEquals(200L, used)
        assertTrue(pendOld.exists() && pendNew.exists())
    }

    @Test fun enforceLimit_cap_countsAllFootageAndStopsUnderCap() {
        val fx = setup()
        media(photos, "2026-10-01_00-00-00_photo.jpg", 100, uploaded = true)
        media(photos, "2026-10-02_00-00-00_photo.jpg", 100)
        media(photos, "2026-10-03_00-00-00_photo.jpg", 100)
        val deleted = ArrayList<File>()
        val used = fx.enforceLimit(250, 50, null, totalBytes = 10_000, freeBytes = 9_000) { deleted.add(it); it.delete() }
        assertEquals(1, deleted.size)
        assertEquals("2026-10-01_00-00-00_photo.jpg", deleted[0].name)
        assertEquals(200L, used)
    }

    @Test fun enforceLimit_neverDeletesTheFileBeingWritten() {
        val fx = setup()
        val current = media(videos, "2026-10-01_00-00-00_motion.mp4", 500)
        val deleted = ArrayList<File>()
        fx.enforceLimit(0, 50, current, totalBytes = 1000, freeBytes = 100) { deleted.add(it); it.delete() }
        assertTrue(deleted.isEmpty())
        assertTrue(current.exists())
    }
}
