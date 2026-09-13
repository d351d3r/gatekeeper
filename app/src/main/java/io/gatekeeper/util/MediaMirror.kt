package io.gatekeeper.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import io.gatekeeper.services.IFileShuttleService
import java.io.File
import java.io.FileInputStream
import java.io.Serializable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фаза 17 (D8): автоперенос скриншотов и фото из рабочего профиля в личний.
 *
 * Модель -- "догон по watermark" (docs/feature_media_mirror.md, открытый вопрос
 * решен в пользу догона): постоянного сервиса нет, перенос запускается при каждом
 * оживлении шаттла (пользователь открыл проводник / File Shuttle). Направление
 * строго одно: тянет ТОЛЬКО личный профиль, пишет только в своё хранилище --
 * запись процессом рабочего профиля в личное невозможна по построению.
 *
 * Выключено по умолчанию; осознанная дырка в изоляции, о чём сказано в настройках.
 */
object MediaMirror {
    private const val TAG = "GatekeeperMediaMirror"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GatekeeperMediaMirror").apply { isDaemon = true }
    }

    fun isEnabled(context: Context): Boolean =
        LocalStorageManager.getInstance()
            .getBoolean(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED) &&
            SettingsManager.getInstance().getCrossProfileFileChooserEnabled()

    /** Точка входа из [FileShuttleConnection.onBinderArrived]; никогда не блокирует вызывающего. */
    fun mirrorIfEnabled(context: Context) {
        val app = context.applicationContext
        // Личный профиль тянет из рабочего; в обратную сторону зеркало не работает --
        // иначе оно выкачивало бы личные медиа в песочницу.
        val enabled = isEnabled(app)
        val work = AntiSpyManager.isWorkProfile(app)
        Log.i(TAG, "shuttle alive: mirror enabled=$enabled, thisIsWork=$work")
        if (!enabled || work) {
            return
        }
        executor.execute {
            try {
                val copied = runPass(app)
                Log.i(TAG, "pass finished: copied $copied file(s)")
            } catch (e: Exception) {
                Log.w(TAG, "media mirror pass failed", e)
            }
        }
    }

    private fun runPass(context: Context): Int {
        val service = FileShuttleConnection.peek() ?: return 0
        val storage = LocalStorageManager.getInstance()
        val watermark = storage.getLong(LocalStorageManager.PREF_MEDIA_MIRROR_WATERMARK, 0L)
        var copied = 0
        var newWatermark = watermark

        for (dir in MediaMirrorSelection.WATCHED_DIRS) {
            // resolvePath шаттла понимает либо абсолютный путь чужого профиля (нам не
            // известен), либо префикс DUMMY_ROOT провайдера; относительный путь он
            // молча клампит в корень. Идём через DUMMY_ROOT, как сам провайдер.
            val files = try {
                service.loadFiles(CrossProfileDocumentsProvider.DUMMY_ROOT + dir)
            } catch (e: Exception) {
                Log.w(TAG, "cannot list $dir in the work profile", e)
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val remoteFiles = files.mapNotNull {
                (it as? Map<String, Serializable>)?.let(::toRemoteFile)
            }
            val selected = MediaMirrorSelection.select(remoteFiles, watermark)
            Log.i(
                TAG, "$dir: listed=${files.size} parsed=${remoteFiles.size} " +
                    "selected=${selected.size} watermark=$watermark"
            )
            for (remote in selected) {
                if (copyOne(context, service, remote, dir)) {
                    copied++
                }
                // Watermark двигаем и для неудачных копий: один плохой файл не должен
                // блокировать догон всех последующих на каждом проходе.
                if (remote.lastModified > newWatermark) {
                    newWatermark = remote.lastModified
                }
            }
        }

        if (newWatermark != watermark) {
            storage.setLong(LocalStorageManager.PREF_MEDIA_MIRROR_WATERMARK, newWatermark)
        }
        return copied
    }

    private fun toRemoteFile(map: Map<String, Serializable>): MediaMirrorSelection.RemoteFile? {
        val path = map[DocumentsContract.Document.COLUMN_DOCUMENT_ID] as? String ?: return null
        val mime = map[DocumentsContract.Document.COLUMN_MIME_TYPE] as? String
        if (mime == null || mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            return null
        }
        return MediaMirrorSelection.RemoteFile(
            path = path,
            name = map[DocumentsContract.Document.COLUMN_DISPLAY_NAME] as? String
                ?: File(path).name,
            mime = mime,
            size = (map[DocumentsContract.Document.COLUMN_SIZE] as? Long) ?: 0L,
            lastModified = (map[DocumentsContract.Document.COLUMN_LAST_MODIFIED] as? Long) ?: 0L,
        )
    }

    private fun copyOne(
        context: Context,
        service: IFileShuttleService,
        remote: MediaMirrorSelection.RemoteFile,
        relativeDir: String,
    ): Boolean {
        val collection =
            if (remote.mime!!.startsWith("video/")) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, remote.name)
            put(MediaStore.MediaColumns.MIME_TYPE, remote.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val inserted = context.contentResolver.insert(collection, values) ?: return false
        return try {
            service.openFile(remote.path, "r").use { remoteFd ->
                context.contentResolver.openOutputStream(inserted)?.use { out ->
                    FileInputStream(remoteFd.fileDescriptor).use { input ->
                        Utility.pipe(input, out)
                    }
                }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(inserted, done, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to mirror ${remote.name}", e)
            try {
                context.contentResolver.delete(inserted, null, null)
            } catch (_: Exception) {
            }
            false
        }
    }
}
