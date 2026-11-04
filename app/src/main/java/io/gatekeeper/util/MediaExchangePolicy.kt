package io.gatekeeper.util

/** Defaults and source boundaries, independent of Android and storage. */
object MediaExchangePolicy {
    const val ENABLED = "media_exchange_enabled"
    const val SCREENSHOTS = "media_exchange_screenshots"
    const val CAMERA = "media_exchange_camera"
    const val REVISION = "media_exchange_revision"
    const val MAX_BYTES = 64L * 1024 * 1024
    const val PAGE_SIZE = 64

    fun defaultEnabled(saved: Boolean?, legacy: Boolean?): Boolean = saved ?: legacy ?: true

    fun allowed(work: Boolean, path: String, mime: String, screenshots: Boolean, camera: Boolean): Boolean {
        val folder = path.trimEnd('/')
        if (!mime.startsWith("image/")) return false
        return if (work) screenshots && folder == "Pictures/Screenshots"
        else camera && folder == "DCIM/Camera"
    }

    fun version(size: Long, modified: Long): String = "$size:$modified"
}
