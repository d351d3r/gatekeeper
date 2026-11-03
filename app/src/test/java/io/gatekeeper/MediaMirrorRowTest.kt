package io.gatekeeper

import android.provider.DocumentsContract.Document
import io.gatekeeper.util.MediaMirror
import io.gatekeeper.util.MediaMirrorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Serializable

/**
 * Разбор строки листинга шаттла в зеркале медиа. Строки строит
 * FileShuttleService.loadFileMeta: размер и время -- Long, у каталога тип MIME_TYPE_DIR.
 */
class MediaMirrorRowTest {
    private fun row(vararg pairs: Pair<String, Serializable>): Map<String, Serializable> = mapOf(*pairs)

    @Test
    fun fileRowIsParsed() {
        val file = MediaMirror.toRemoteFile(
            row(
                Document.COLUMN_DOCUMENT_ID to "/storage/emulated/10/DCIM/Camera/a.jpg",
                Document.COLUMN_DISPLAY_NAME to "a.jpg",
                Document.COLUMN_MIME_TYPE to "image/jpeg",
                Document.COLUMN_SIZE to 2048L,
                Document.COLUMN_LAST_MODIFIED to 1_700_000_000_000L,
            )
        )
        assertEquals(
            MediaMirrorSelection.RemoteFile(
                path = "/storage/emulated/10/DCIM/Camera/a.jpg",
                name = "a.jpg",
                mime = "image/jpeg",
                size = 2048L,
                lastModified = 1_700_000_000_000L,
            ),
            file,
        )
    }

    /** Листинг корня вместо каталога (E-7) состоит из каталогов: ни один не должен уехать как файл. */
    @Test
    fun directoriesAndUntypedRowsAreSkipped() {
        assertNull(
            MediaMirror.toRemoteFile(
                row(
                    Document.COLUMN_DOCUMENT_ID to "/storage/emulated/10/DCIM",
                    Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                )
            )
        )
        assertNull(MediaMirror.toRemoteFile(row(Document.COLUMN_DOCUMENT_ID to "/storage/emulated/10/x.bin")))
        assertNull(MediaMirror.toRemoteFile(row(Document.COLUMN_MIME_TYPE to "image/png")))
    }

    @Test
    fun missingNameFallsBackToTheFileName() {
        val file = MediaMirror.toRemoteFile(
            row(
                Document.COLUMN_DOCUMENT_ID to "/storage/emulated/10/Pictures/Screenshots/s.png",
                Document.COLUMN_MIME_TYPE to "image/png",
            )
        )
        assertEquals("s.png", file?.name)
    }

    /** Размер не того типа читается как 0, и отбор такой файл не переносит -- не копия неизвестного объема. */
    @Test
    fun sizeOfAnotherTypeIsNotTrusted() {
        val file = MediaMirror.toRemoteFile(
            row(
                Document.COLUMN_DOCUMENT_ID to "/storage/emulated/10/DCIM/Camera/v.mp4",
                Document.COLUMN_MIME_TYPE to "video/mp4",
                Document.COLUMN_SIZE to 2048,
                Document.COLUMN_LAST_MODIFIED to 5L,
            )
        )!!
        assertEquals(0L, file.size)
        assertTrue(MediaMirrorSelection.select(listOf(file), watermarkMs = 0L).isEmpty())
    }
}
