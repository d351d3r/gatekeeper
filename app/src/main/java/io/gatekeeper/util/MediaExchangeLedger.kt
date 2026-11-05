package io.gatekeeper.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Durable discovery cursor + delivery journal. Discovery advances only after enqueue commits. */
@Suppress("TooManyFunctions") // Thin operations of one durable SQL journal.
class MediaExchangeLedger(context: Context, name: String = "media-exchange.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    data class Delivery(val token: String, val source: String, val id: Long, val uri: String?, val attempts: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources (source TEXT PRIMARY KEY, cursor INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE deliveries (token TEXT PRIMARY KEY, source TEXT NOT NULL, " +
            "media_id INTEGER NOT NULL, " +
            "uri TEXT, done INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, " +
            "retry_at INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun cursor(source: String): Long? = readableDatabase.rawQuery(
        "SELECT cursor FROM sources WHERE source=?", arrayOf(source),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun baseline(source: String, id: Long) {
        writableDatabase.insertOrThrow("sources", null, ContentValues().apply {
            put("source", source); put("cursor", id)
        })
    }

    fun discover(source: String, items: List<MediaExchangeItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items.filter { it.mime.isNotEmpty() }) {
                db.insertWithOnConflict("deliveries", null, ContentValues().apply {
                    put("token", token(source, item.id)); put("source", source); put("media_id", item.id)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.update("sources", ContentValues().apply { put("cursor", items.last().id) }, "source=?", arrayOf(source))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Suppress("MagicNumber") // Fixed SELECT column positions, not tunable parameters.
    fun pending(source: String, now: Long): List<Delivery> = readableDatabase.rawQuery(
        "SELECT token, source, media_id, uri, attempts FROM deliveries " +
            "WHERE source=? AND done=0 AND retry_at<=? LIMIT 16",
        arrayOf(source, now.toString()),
    ).use { c -> buildList {
        while (c.moveToNext()) add(Delivery(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4)))
    } }

    fun setUri(token: String, uri: String?) = update(token, ContentValues().apply { put("uri", uri) })
    fun deliveredCount(source: String): Long = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM deliveries WHERE source=? AND done=1 AND uri IS NOT NULL", arrayOf(source),
    ).use { it.moveToFirst(); it.getLong(0) }
    fun hasPending(source: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM deliveries WHERE source=? AND done=0 LIMIT 1", arrayOf(source),
    ).use { it.moveToFirst() }
    fun complete(token: String) = update(token, ContentValues().apply { put("done", 1) })
    fun retry(delivery: Delivery, now: Long) = update(delivery.token, ContentValues().apply {
        put("attempts", delivery.attempts + 1)
        put("retry_at", now + retryDelay(delivery.attempts))
    })
    private fun update(token: String, values: ContentValues) {
        writableDatabase.update("deliveries", values, "token=?", arrayOf(token))
    }

    companion object {
        private const val MAX_BACKOFF_POWER = 8
        private const val MILLIS_PER_SECOND = 1000L
        fun token(source: String, id: Long): String = MessageDigest.getInstance("SHA-256")
            .digest("$source:$id".toByteArray()).joinToString("") { "%02x".format(it) }
        fun retryDelay(attempts: Int): Long = (1L shl attempts.coerceIn(0, MAX_BACKOFF_POWER)) * MILLIS_PER_SECOND
    }
}
