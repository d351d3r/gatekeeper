package io.gatekeeper

import android.provider.DocumentsContract.Document
import io.gatekeeper.util.MediaMirror
import io.gatekeeper.util.MediaMirrorSelection
import io.gatekeeper.util.ShuttleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор записи листинга шаттла в зеркале медиа. Записи строит
 * FileShuttleService.loadFileMeta: у каталога тип MIME_TYPE_DIR, у нейтрального
 * отказа -- только путь и нулевые флаги.
 */
class MediaMirrorRowTest {
    @Test
    fun fileEntryIsParsed() {
        val file = MediaMirror.toRemoteFile(
            ShuttleEntry(
                documentId = "/storage/emulated/10/DCIM/Camera/a.jpg",
                displayName = "a.jpg",
                mimeType = "image/jpeg",
                size = 2048L,
                lastModified = 1_700_000_000_000L,
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
    fun directoriesUntypedAndPathlessEntriesAreSkipped() {
        assertNull(
            MediaMirror.toRemoteFile(
                ShuttleEntry(documentId = "/storage/emulated/10/DCIM", mimeType = Document.MIME_TYPE_DIR)
            )
        )
        assertNull(MediaMirror.toRemoteFile(ShuttleEntry(documentId = "/storage/emulated/10/x.bin")))
        assertNull(MediaMirror.toRemoteFile(ShuttleEntry(documentId = "", mimeType = "image/png", size = 1L)))
    }

    @Test
    fun missingNameFallsBackToTheFileName() {
        val file = MediaMirror.toRemoteFile(
            ShuttleEntry(documentId = "/storage/emulated/10/Pictures/Screenshots/s.png", mimeType = "image/png")
        )
        assertEquals("s.png", file?.name)
    }

    /** Нейтральный отказ без размера читается как 0, и отбор такой файл не переносит. */
    @Test
    fun entryWithoutSizeIsNotMirrored() {
        val file = MediaMirror.toRemoteFile(
            ShuttleEntry(
                documentId = "/storage/emulated/10/DCIM/Camera/v.mp4",
                mimeType = "video/mp4",
                lastModified = 5L,
            )
        )!!
        assertEquals(0L, file.size)
        assertTrue(MediaMirrorSelection.select(listOf(file), watermarkMs = 0L).isEmpty())
    }
}

/** Запись шаттла -> колонки курсора провайдера документов. */
class ShuttleEntryColumnsTest {
    private val documentColumns = listOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_FLAGS,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    @Test
    fun everyProviderColumnIsMapped() {
        val entry = ShuttleEntry(
            documentId = "/storage/emulated/10/DCIM/Camera/a.jpg",
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            size = 2048L,
            lastModified = 7L,
            flags = Document.FLAG_SUPPORTS_DELETE,
        )
        assertEquals(
            listOf<Any?>(entry.documentId, "a.jpg", Document.FLAG_SUPPORTS_DELETE, "image/jpeg", 2048L, 7L),
            documentColumns.map(entry::column),
        )
    }

    /** Нейтральный отказ: в курсоре только путь и нулевые флаги, как и было с картой. */
    @Test
    fun refusalEntryLeavesOtherColumnsEmpty() {
        val refusal = ShuttleEntry(documentId = "relative/path")
        assertEquals(
            listOf<Any?>("relative/path", null, 0, null, null, null),
            documentColumns.map(refusal::column),
        )
    }

    @Test
    fun unknownColumnIsNull() {
        assertNull(ShuttleEntry(documentId = "/x").column(Document.COLUMN_ICON))
    }
}
