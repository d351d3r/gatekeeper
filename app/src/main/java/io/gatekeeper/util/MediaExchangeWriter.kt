package io.gatekeeper.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Own-profile writes only. Stable names recover an insert even if the URI journal write was interrupted. */
class MediaExchangeWriter(private val context: Context, private val work: Boolean) {
    private val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val directory = "Pictures/Gatekeeper/" + if (work) "Personal Camera/" else "Work Screenshots/"

    @Suppress("ReturnCount") // Legacy file, recovered row, or a newly inserted row.
    fun prepare(delivery: MediaExchangeLedger.Delivery, item: MediaExchangeItem): Uri {
        val ext = when (item.mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/jpeg" -> "jpg"
            "image/avif" -> "avif"
            "image/gif" -> "gif"
            else -> "img"
        }
        val name = "gk_${delivery.token}.$ext"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val file = File(Environment.getExternalStorageDirectory(), directory + name + ".pending")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            return Uri.fromFile(file)
        }
        // Include our own pending rows in crash recovery on Android 10+.
        @Suppress("DEPRECATION")
        val includingPending = MediaStore.setIncludePending(collection)
        context.contentResolver.query(includingPending, arrayOf("_id"),
            "_display_name=? AND relative_path=? AND owner_package_name=?",
            arrayOf(name, directory, context.packageName), null)?.use { c ->
            if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, item.taken)
        }) ?: throw IOException("Cannot create image")
    }

    @Suppress("ThrowsCount") // Every partial-copy failure must prevent publication.
    fun write(uri: Uri, input: InputStream, expectedSize: Long, allowed: () -> Boolean) {
        require(expectedSize in 1..MediaExchangePolicy.MAX_BYTES)
        val output = if (uri.scheme == "file") File(requireNotNull(uri.path)).outputStream()
        else context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination")
        output.use { out ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                if (!allowed()) throw IOException("Exchange disabled or configuration changed")
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > expectedSize) throw IOException("Source changed while copying")
                out.write(buffer, 0, count)
            }
            if (copied != expectedSize) throw IOException("Incomplete image")
        }
    }

    fun publish(uri: Uri, item: MediaExchangeItem) {
        if (uri.scheme == "file") {
            val temporary = File(requireNotNull(uri.path))
            val final = File(temporary.path.removeSuffix(".pending"))
            check(temporary.renameTo(final))
            MediaScannerConnection.scanFile(context, arrayOf(final.path), arrayOf(item.mime), null)
        } else {
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, item.taken)
            }, null, null) == 1)
        }
    }

    fun isPublished(uri: Uri, expectedSize: Long): Boolean {
        if (uri.scheme == "file") return File(requireNotNull(uri.path).removeSuffix(".pending")).let {
            it.isFile && it.length() == expectedSize
        }
        return context.contentResolver.query(uri, arrayOf("is_pending", "_size"), null, null, null)
            ?.use { it.moveToFirst() && it.getInt(0) == 0 && it.getLong(1) == expectedSize } ?: false
    }

    fun discardPending(uri: Uri) {
        if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
        else context.contentResolver.delete(uri, "is_pending=1", null)
    }

    fun cleanPending() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val uri = MediaStore.setIncludePending(collection)
            context.contentResolver.delete(uri, "is_pending=1 AND relative_path=? AND owner_package_name=?",
                arrayOf(directory, context.packageName))
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), directory)
            dir.listFiles()?.filter { it.name.startsWith("gk_") && it.name.endsWith(".pending") }
                ?.forEach { it.delete() }
        }
    }
    companion object { private const val COPY_BUFFER_BYTES = 64 * 1024 }
}
