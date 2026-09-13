package io.gatekeeper

import io.gatekeeper.util.MediaMirrorSelection
import io.gatekeeper.util.MediaMirrorSelection.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMirrorSelectionTest {
    private fun file(
        name: String,
        mime: String? = "image/png",
        size: Long = 100,
        mtime: Long = 1_000,
    ) = RemoteFile(path = "/work/Pictures/Screenshots/$name", name = name, mime = mime, size = size, lastModified = mtime)

    @Test
    fun selectsOnlyImagesAndVideos() {
        val picked = MediaMirrorSelection.select(
            listOf(
                file("shot.png"),
                file("clip.mp4", mime = "video/mp4"),
                file("note.txt", mime = "text/plain"),
                file("secret.bin", mime = null),
            ),
            watermarkMs = 0,
        )
        assertEquals(listOf("shot.png", "clip.mp4"), picked.map { it.name })
    }

    @Test
    fun watermarkSkipsAlreadyMirroredFiles() {
        val picked = MediaMirrorSelection.select(
            listOf(file("old.png", mtime = 500), file("new.png", mtime = 1_500)),
            watermarkMs = 1_000,
        )
        assertEquals(listOf("new.png"), picked.map { it.name })
    }

    @Test
    fun sizeBoundsRejectEmptyAndHuge() {
        val picked = MediaMirrorSelection.select(
            listOf(
                file("empty.png", size = 0),
                file("ok.png", size = MediaMirrorSelection.MAX_FILE_BYTES),
                file("huge.mp4", mime = "video/mp4", size = MediaMirrorSelection.MAX_FILE_BYTES + 1),
            ),
            watermarkMs = 0,
        )
        assertEquals(listOf("ok.png"), picked.map { it.name })
    }

    @Test
    fun resultSortedByModificationTime() {
        val picked = MediaMirrorSelection.select(
            listOf(file("c.png", mtime = 30), file("a.png", mtime = 10), file("b.png", mtime = 20)),
            watermarkMs = 0,
        )
        assertEquals(listOf("a.png", "b.png", "c.png"), picked.map { it.name })
    }

    @Test
    fun watchedDirsAreTheContractedTwo() {
        assertEquals(listOf("Pictures/Screenshots", "DCIM/Camera"), MediaMirrorSelection.WATCHED_DIRS)
        assertTrue(MediaMirrorSelection.isImageOrVideo("image/jpeg"))
        assertFalse(MediaMirrorSelection.isImageOrVideo("application/pdf"))
    }
}
