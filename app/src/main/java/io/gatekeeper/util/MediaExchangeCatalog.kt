package io.gatekeeper.util

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Only approved local folders can be enumerated or opened through the media endpoint. */
class MediaExchangeCatalog(private val context: Context, private val work: Boolean) {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun queryCollection(): android.net.Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return collection
        @Suppress("DEPRECATION")
        return MediaStore.setIncludePending(collection)
    }

    @Synchronized
    fun identity(): String {
        val s = LocalStorageManager.getInstance()
        val install = s.getString(IDENTITY) ?: UUID.randomUUID().toString().also { s.setStringNow(IDENTITY, it) }
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getVersion(context) else "legacy"
        return "$install:$version"
    }

    fun latestId(): Long = context.contentResolver.query(
        queryCollection(), arrayOf("_id"), null, null, "_id DESC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: error("MediaStore unavailable")

    fun list(after: Long, limit: Int, config: MediaExchangeSettings.Config): List<MediaExchangeItem> {
        val out = ArrayList<MediaExchangeItem>()
        context.contentResolver.query(queryCollection(), projection(), "_id > ?", arrayOf(after.toString()), "_id ASC")
            ?.use { cursor ->
                while (out.size < limit.coerceIn(1, MediaExchangePolicy.PAGE_SIZE) && cursor.moveToNext()) {
                    // Return non-source rows too, as metadata-only tombstones, so pagination advances.
                    val item = read(cursor, config)
                    out.add(item)
                }
            } ?: error("MediaStore unavailable")
        return out
    }

    fun describe(id: Long, config: MediaExchangeSettings.Config): MediaExchangeItem? =
        context.contentResolver.query(ContentUris.withAppendedId(queryCollection(), id), projection(), null, null, null)
            ?.use { if (it.moveToFirst()) read(it, config) else null }

    @Suppress("ThrowsCount") // Missing, unstable and unreadable media are distinct refusals.
    fun open(id: Long, version: String, config: MediaExchangeSettings.Config): ParcelFileDescriptor {
        val item = describe(id, config) ?: throw FileNotFoundException()
        if (!item.ready || item.version != version || item.size !in 1..MediaExchangePolicy.MAX_BYTES) {
            throw FileNotFoundException("Image is unavailable or changed")
        }
        return context.contentResolver.openFileDescriptor(ContentUris.withAppendedId(collection, id), "r")
            ?: throw FileNotFoundException()
    }

    private fun projection(): Array<String> = buildList {
        addAll(listOf(
        "_id", "_display_name", "mime_type", "_size", "date_modified", "datetaken",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "relative_path" else "_data",
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("is_pending")
    }.toTypedArray()

    @Suppress("MagicNumber") // Positions in the adjacent fixed projection.
    private fun read(c: Cursor, config: MediaExchangeSettings.Config): MediaExchangeItem {
        val path = c.getString(6).orEmpty()
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) path else {
            @Suppress("DEPRECATION")
            val root = android.os.Environment.getExternalStorageDirectory().canonicalPath + "/"
            File(path).parent.orEmpty().removePrefix(root)
        }
        val mime = c.getString(2).orEmpty()
        val allowed = config.enabled &&
            MediaExchangePolicy.allowed(work, folder, mime, config.screenshots, config.camera)
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c.getInt(7) != 0
        // An empty MIME is a tombstone, never an exportable source. Pending rows retain their MIME for retry.
        return MediaExchangeItem(c.getLong(0), if (allowed) c.getString(1).orEmpty().take(512) else "",
            if (allowed) mime else if (pending) "pending" else "",
            if (allowed) c.getLong(3) else 0, if (allowed) c.getLong(4) else 0,
            if (allowed) c.getLong(5) else 0, allowed && !pending)
    }

    companion object { private const val IDENTITY = "media_exchange_install_id" }
}
