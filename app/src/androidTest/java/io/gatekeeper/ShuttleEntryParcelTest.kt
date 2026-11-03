package io.gatekeeper

import android.os.Parcel
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.util.ShuttleEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Запись File Shuttle проходит через Parcel без потерь: это формат ответа между
 * профилями, и поле, потерянное при записи или чтении, ломает проводник и перенос медиа.
 */
@RunWith(AndroidJUnit4::class)
class ShuttleEntryParcelTest {
    private fun roundTrip(entry: ShuttleEntry): ShuttleEntry {
        val parcel = Parcel.obtain()
        try {
            entry.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ShuttleEntry.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun fullEntryRoundTrips() {
        val entry = ShuttleEntry(
            documentId = "/storage/emulated/10/DCIM/Camera/a.jpg",
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            size = 2048L,
            lastModified = 1_700_000_000_000L,
            flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_THUMBNAIL,
        )
        assertEquals(entry, roundTrip(entry))
    }

    /** Нейтральный отказ: пустые поля остаются пустыми, а не превращаются в 0 или "". */
    @Test
    fun refusalEntryKeepsItsGaps() {
        val refusal = ShuttleEntry(documentId = "relative/path")
        assertEquals(refusal, roundTrip(refusal))
    }

    /** Ответ loadFiles -- типизированный список, как его пишет и читает сгенерированный AIDL. */
    @Test
    fun typedListRoundTrips() {
        val entries = listOf(
            ShuttleEntry(documentId = "/a", mimeType = Document.MIME_TYPE_DIR, size = 0L, lastModified = 1L),
            ShuttleEntry(documentId = "/a/b.png", displayName = "b.png", mimeType = "image/png", size = 9L),
        )
        val parcel = Parcel.obtain()
        try {
            parcel.writeTypedList(entries)
            parcel.setDataPosition(0)
            assertEquals(entries, parcel.createTypedArrayList(ShuttleEntry.CREATOR))
        } finally {
            parcel.recycle()
        }
    }
}
