package io.gatekeeper.util

import org.json.JSONObject

data class MediaExchangeItem(
    val id: Long, val name: String, val mime: String, val size: Long,
    val modified: Long, val taken: Long, val ready: Boolean,
) {
    val version: String get() = MediaExchangePolicy.version(size, modified)
    fun wire(): String = JSONObject().put("id", id).put("name", name).put("mime", mime)
        .put("size", size).put("modified", modified).put("taken", taken).put("ready", ready).toString()

    companion object {
        private const val MAX_METADATA_CHARACTERS = 8192
        fun parse(wire: String): MediaExchangeItem {
            require(wire.length <= MAX_METADATA_CHARACTERS)
            val o = JSONObject(wire)
            return MediaExchangeItem(o.getLong("id"), o.getString("name"), o.getString("mime"),
                o.getLong("size"), o.getLong("modified"), o.getLong("taken"), o.getBoolean("ready"))
        }
    }
}
