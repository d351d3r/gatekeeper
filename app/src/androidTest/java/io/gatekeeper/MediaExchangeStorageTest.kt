package io.gatekeeper

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.util.MediaExchangeItem
import io.gatekeeper.util.MediaExchangeLedger
import io.gatekeeper.util.MediaExchangeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite/MediaStore checks; creates and removes only this test's own records. */
@RunWith(AndroidJUnit4::class)
class MediaExchangeStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun discoveredFilesSurviveReopenAndFailuresDoNotAdvanceDelivery() {
        val name = "media-exchange-test-${UUID.randomUUID()}.db"
        val source = "test-source"
        try {
            MediaExchangeLedger(context, name).use { db ->
                db.baseline(source, 5)
                db.discover(source, listOf(item(6), item(7)))
                val entries = db.pending(source, 10000)
                assertEquals(2, entries.size)
                db.complete(entries.first().token)
                db.retry(entries.last(), 10000)
                assertTrue(db.pending(source, 10000).isEmpty())
            }
            MediaExchangeLedger(context, name).use { db ->
                assertEquals(7L, db.cursor(source))
                val pending = db.pending(source, 12000)
                assertEquals(listOf(7L), pending.map { it.id })
                db.discover(source, listOf(item(6), item(7)))
                assertEquals(1, db.pending(source, 12000).size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun pendingInsertionIsRecoveredAndOnlyPublishedAfterCompleteCopy() {
        val writer = MediaExchangeWriter(context, false)
        val delivery = MediaExchangeLedger.Delivery(UUID.randomUUID().toString().replace("-", ""), "test", 1, null, 0)
        val bytes = ByteArray(128) { it.toByte() }
        val image = item(1).copy(size = bytes.size.toLong())
        var uri: Uri? = null
        try {
            uri = writer.prepare(delivery, image)
            assertEquals(uri, writer.prepare(delivery, image)) // crash before URI was recorded
            assertFalse(writer.isPublished(uri, image.size))
            writer.write(uri, bytes.inputStream(), image.size) { true }
            assertFalse(writer.isPublished(uri, image.size))
            writer.publish(uri, image)
            assertTrue(writer.isPublished(uri, image.size))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            writer.discardPending(uri)
            assertTrue(writer.isPublished(uri, image.size)) // disabling must not delete completed copies
        } finally { uri?.let { context.contentResolver.delete(it, null, null) } }
    }

    private fun item(id: Long) = MediaExchangeItem(id, "test.png", "image/png", 128, 100, 100, true)
}
