package io.gatekeeper.util

import org.json.JSONObject

/** Personal profile owns configuration. Work profile never uses local defaults to export data. */
object MediaExchangeSettings {
    data class Config(
        val enabled: Boolean, val screenshots: Boolean, val camera: Boolean, val revision: Long,
        val screenshotEpoch: Long = 0, val cameraEpoch: Long = 0,
    ) {
        fun wire(): String = JSONObject().put("enabled", enabled).put("screenshots", screenshots)
            .put("camera", camera).put("revision", revision)
            .put("screenshotEpoch", screenshotEpoch).put("cameraEpoch", cameraEpoch).toString()
    }

    fun current(): Config {
        val s = LocalStorageManager.getInstance()
        val enabled = MediaExchangePolicy.defaultEnabled(
            s.getBoolean(MediaExchangePolicy.ENABLED).takeIf { s.contains(MediaExchangePolicy.ENABLED) },
            s.getBoolean(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED)
                .takeIf { s.contains(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED) },
        )
        return Config(enabled, s.getBoolean(MediaExchangePolicy.SCREENSHOTS, true),
            s.getBoolean(MediaExchangePolicy.CAMERA, true), s.getLong(MediaExchangePolicy.REVISION, 0),
            s.getLong("media_exchange_screenshot_epoch", 0), s.getLong("media_exchange_camera_epoch", 0))
    }

    @Synchronized
    fun set(key: String, enabled: Boolean) {
        require(key in setOf(MediaExchangePolicy.ENABLED, MediaExchangePolicy.SCREENSHOTS, MediaExchangePolicy.CAMERA))
        val s = LocalStorageManager.getInstance()
        val before = current()
        if (enabled && key == MediaExchangePolicy.ENABLED && !before.enabled) {
            s.setLongNow("media_exchange_screenshot_epoch", before.screenshotEpoch + 1)
            s.setLongNow("media_exchange_camera_epoch", before.cameraEpoch + 1)
        } else if (enabled && key == MediaExchangePolicy.SCREENSHOTS && !before.screenshots) {
            s.setLongNow("media_exchange_screenshot_epoch", before.screenshotEpoch + 1)
        } else if (enabled && key == MediaExchangePolicy.CAMERA && !before.camera) {
            s.setLongNow("media_exchange_camera_epoch", before.cameraEpoch + 1)
        }
        s.setBooleanNow(key, enabled)
        // Persist before notifying the other profile; a stale in-flight copy cannot publish.
        s.setLongNow(MediaExchangePolicy.REVISION, current().revision + 1)
    }

    fun parse(wire: String): Config {
        val o = JSONObject(wire)
        return Config(o.getBoolean("enabled"), o.getBoolean("screenshots"),
            o.getBoolean("camera"), o.getLong("revision"), o.getLong("screenshotEpoch"), o.getLong("cameraEpoch"))
    }
}
