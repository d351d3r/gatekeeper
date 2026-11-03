package io.gatekeeper.services

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import io.gatekeeper.R
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.util.BinderCallers
import io.gatekeeper.util.CrossProfileDocumentsProvider
import io.gatekeeper.util.FileShuttlePathResolver
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.ShuttleEntry
import io.gatekeeper.util.StatusNotification
import io.gatekeeper.util.Utility
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class FileShuttleService : Service() {
    private val suicideTask = Runnable { suicide() }
    private val handler = Handler(Looper.getMainLooper())

    private val stub = object : IFileShuttleService.Stub() {
        /**
         * Биндер шаттла отдается другому профилю через колбэк из extras интента. Чужое
         * приложение, получившее его, раньше читало и писало все общее хранилище этого
         * профиля. Методы исполняются только для Gatekeeper (S1, docs/audit_android17.md).
         */
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            BinderCallers.enforceGatekeeperCaller(code)
            return super.onTransact(code, data, reply, flags)
        }

        override fun ping() {
            resetSuicideTask()
        }

        override fun loadFiles(path: String): List<ShuttleEntry> {
            resetSuicideTask()
            val resolved = resolvePath(path) ?: return emptyList()
            return File(resolved).listFiles()?.map { child -> loadFileMeta(child.path) }.orEmpty()
        }

        override fun loadFileMeta(path: String): ShuttleEntry {
            resetSuicideTask()
            // Нейтральный отказ: документ без флагов и с исходным id, чтобы
            // клиент видел запись, но не мог ни открыть, ни удалить её.
            val resolved = resolvePath(path) ?: return ShuttleEntry(documentId = path)
            val f = File(resolved)
            val displayName = if (f == Environment.getExternalStorageDirectory()) {
                getString(R.string.app_name)
            } else {
                f.name
            }
            val mime: String
            val flags: Int
            if (f.isDirectory) {
                mime = DocumentsContract.Document.MIME_TYPE_DIR
                flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            } else {
                val guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                val hasThumbnail = guessed != null && (guessed.startsWith("image/") || guessed.startsWith("video/"))
                mime = guessed ?: "application/unknown"
                flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    (if (hasThumbnail) DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            }
            return ShuttleEntry(
                documentId = f.absolutePath,
                displayName = displayName,
                mimeType = mime,
                size = f.length(),
                lastModified = f.lastModified(),
                flags = flags,
            )
        }

        override fun openFile(path: String, mode: String): ParcelFileDescriptor? {
            resetSuicideTask()
            val resolved = resolvePath(path) ?: return null
            val f = File(resolved)
            val numericMode = ParcelFileDescriptor.parseMode(mode)

            return try {
                if (numericMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
                    ParcelFileDescriptor.open(f, numericMode, handler) {
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                        notifyMediaScannerIfNecessary(f, mime)
                    }
                } else {
                    ParcelFileDescriptor.open(f, numericMode)
                }
            } catch (_: IOException) {
                null
            }
        }

        override fun openThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
            resetSuicideTask()
            return resolvePath(path)
                ?.let { fullPath ->
                    MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(Utility.getFileExtension(fullPath))
                        ?.let { mime -> thumbnailFor(mime, fullPath, sizeHint) }
                }
        }

        override fun createFile(path: String, mimeType: String, displayName: String): String? {
            resetSuicideTask()
            var fullPath = "$path/$displayName"
            val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
            val shouldAppendExtension = mimeType.isNotEmpty() && !isDirectory &&
                mimeType != "application/octet-stream"

            if (shouldAppendExtension) {
                val extensionPart = "." +
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (!fullPath.endsWith(extensionPart)) {
                    fullPath += extensionPart
                }
            }

            val resolved = resolvePath(fullPath) ?: return null
            val f = File(resolved)
            return try {
                if ((isDirectory && !f.mkdir()) || (!isDirectory && !f.createNewFile())) {
                    null
                } else {
                    notifyMediaScannerIfNecessary(f, mimeType)
                    f.absolutePath
                }
            } catch (_: IOException) {
                null
            }
        }

        override fun deleteFile(path: String): String {
            resetSuicideTask()
            val resolved = resolvePath(path) ?: return path
            val f = File(resolved)
            f.delete()
            return (f.parentFile ?: f).absolutePath
        }

        override fun isChildOf(parent: String, child: String): Boolean {
            // Клиент считает срок связи от отправки любого дошедшего вызова, поэтому таймер
            // обязан сбрасывать каждый метод стаба без исключений.
            resetSuicideTask()
            return isChildResolved(parent, child) ?: false
        }
    }

    private fun isChildResolved(parent: String, child: String): Boolean? {
        val parentResolved = resolvePath(parent)
        val childResolved = resolvePath(child)
        if (parentResolved == null || childResolved == null) return null
        val parentFile = File(parentResolved)
        val childFile = File(childResolved)
        var parentPath = parentFile.absolutePath
        if (parentPath[parentPath.length - 1] != '/') {
            parentPath += "/"
        }
        return parentFile.exists() && parentFile.isDirectory &&
            childFile.exists() &&
            childFile.absolutePath.startsWith(parentPath)
    }

    private fun thumbnailFor(mime: String, fullPath: String, sizeHint: Point): ParcelFileDescriptor? =
        when {
            mime.startsWith("image/") -> loadImageThumbnail(fullPath, sizeHint)
            mime.startsWith("video/") -> loadVideoThumbnail(fullPath, sizeHint)
            else -> null
        }

    override fun onBind(intent: Intent?): IBinder {
        resetSuicideTask()
        return stub
    }

    // Перезапускать сервис после смерти незачем: связь заново устанавливает клиент, а не
    // система.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        resetSuicideTask()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("FileShuttleService", "being destroyed")
    }

    /**
     * Замеры на эмуляторе: без переднего плана процесс живет в кэше
     * (`cch-started-ui-services`) и замерзает через 10 с после ухода реле с экрана, а первая
     * же синхронная транзакция в замороженный процесс заканчивается
     * `Killing ... Sync transaction while frozen`. Обычный запущенный сервис от кэша не
     * спасает -- только передний план. Уведомление живет столько же, сколько сессия.
     */
    private fun goForeground() {
        val notification = Notifications.buildNotification(
            this,
            StatusNotification(
                getString(R.string.app_name),
                getString(R.string.file_shuttle_service_title),
                getString(R.string.file_shuttle_service_desc),
                R.drawable.ic_notification,
            ),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // Связь при этом работает, но процесс остается кэшированным: он замерзнет через
            // несколько секунд, и первая же синхронная транзакция клиента будет для него
            // последней. Поэтому удерживающий пинг сделан асинхронным -- убить процесс может
            // только запрос самого пользователя, и клиент об этом узнает из linkToDeath.
            android.util.Log.w("FileShuttleService", "startForeground failed", e)
        }
    }

    /**
     * С Android 15 тип `dataSync` живет не дольше шести часов в сутки суммарно. Дальше
     * система требует остановиться сама, и без этого сервис падает с
     * `RemoteServiceException`. Сессия все равно измеряется минутами, так что терять нечего.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w("FileShuttleService", "foreground time limit reached, releasing")
        suicide()
    }

    // Единственный корень, который сервис имеет право трогать. Документ-Id приходят двух видов:
    // с префиксом DUMMY_ROOT (прямые вызовы провайдера) и уже развернутые абсолютные пути
    // (loadFileMeta отдает COLUMN_DOCUMENT_ID как абсолютный путь, и DocumentsUI возвращает их
    // как есть). Контракт и отказ -- в FileShuttlePathResolver: вне его стаб возвращает
    // нейтральный отказ (пустой список/null/false), а не клампит в корень (E-7).
    private fun resolvePath(path: String): String? {
        val resolved = FileShuttlePathResolver.resolve(
            path,
            CrossProfileDocumentsProvider.DUMMY_ROOT,
            Environment.getExternalStorageDirectory(),
        )
        if (resolved == null) {
            android.util.Log.w("FileShuttleService", "refused document id outside shuttle root: $path")
        }
        return resolved
    }

    private fun resetSuicideTask() {
        handler.removeCallbacks(suicideTask)
        handler.postDelayed(suicideTask, TIMEOUT)
    }

    private fun suicide() {
        handler.removeCallbacks(suicideTask)
        (application as GatekeeperApplication).unbindFileShuttleService()
        stopSelf()
    }

    /**
     * С API 29 миниатюру строит [ThumbnailUtils] прямо по файлу (с учетом EXIF-поворота);
     * таблица MediaStore.Images.Thumbnails там устарела и пуста, поэтому старый путь
     * через нее оставлен только для API < 29.
     */
    private fun loadImageThumbnail(fullPath: String, sizeHint: Point): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bitmapToFd(createThumbnail(File(fullPath), sizeHint, video = false))
        } else {
            loadImageThumbnailLegacy(fullPath, sizeHint)
        }

    private fun loadVideoThumbnail(fullPath: String, sizeHint: Point): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bitmapToFd(createThumbnail(File(fullPath), sizeHint, video = true))
        } else {
            @Suppress("DEPRECATION") // единственный API миниатюр видео до Android 10
            bitmapToFd(
                ThumbnailUtils.createVideoThumbnail(fullPath, MediaStore.Video.Thumbnails.MINI_KIND)
            )
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createThumbnail(file: File, sizeHint: Point, video: Boolean): Bitmap? {
        val size = Size(sizeHint.x.coerceAtLeast(1), sizeHint.y.coerceAtLeast(1))
        return try {
            if (video) {
                ThumbnailUtils.createVideoThumbnail(file, size, null)
            } else {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            }
        } catch (_: IOException) {
            null
        }
    }

    @Suppress("DEPRECATION") // MediaStore.Images.Thumbnails: путь только для API < 29
    private fun loadImageThumbnailLegacy(fullPath: String, sizeHint: Point): ParcelFileDescriptor? {
        val id = Utility.getMediaStoreId(this, fullPath)
        if (id == -1) {
            return loadBitmapThumbnail(fullPath, sizeHint)
        }
        val query = {
            MediaStore.Images.Thumbnails.queryMiniThumbnail(
                contentResolver,
                id.toLong(),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
        var result = query()
        if (result == null || result.count == 0) {
            result?.close()
            MediaStore.Images.Thumbnails.getThumbnail(
                contentResolver,
                id.toLong(),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
            result = query()
        }
        return result.use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return@use loadBitmapThumbnail(fullPath, sizeHint)
            }
            try {
                val index = cursor.getColumnIndex(MediaStore.Images.Thumbnails.DATA)
                contentResolver.openFileDescriptor(Uri.fromFile(File(cursor.getString(index))), "r")
            } catch (_: FileNotFoundException) {
                null
            }
        }
    }

    private fun loadBitmapThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
        val bmp = Utility.decodeSampledBitmap(path, sizeHint.x, sizeHint.y) ?: return null
        return bitmapToFd(bmp)
    }

    private fun bitmapToFd(bmp: Bitmap?): ParcelFileDescriptor? {
        if (bmp == null) return null
        val pair = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            return null
        }

        Thread {
            // AutoCloseOutputStream закрывает сам ParcelFileDescriptor, а не только поток
            // поверх его дескриптора: иначе пишущий конец трубы оставался незакрытым объектом.
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pair[1]).use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }
            } catch (_: IOException) {
            }
            bmp.recycle()
        }.start()

        return pair[0]
    }

    /**
     * Новый файл должен сразу появиться в галерее. Broadcast ACTION_MEDIA_SCANNER_SCAN_FILE
     * устарел с API 29; MediaScannerConnection.scanFile -- поддерживаемая замена.
     */
    private fun notifyMediaScannerIfNecessary(f: File, mimeType: String?) {
        if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(mimeType), null)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x5417

        /**
         * Простой, после которого сервис убивает себя. Клиент удерживает его пингами вдвое
         * чаще; при 10 с период пингов упирался бы в 5 с, а без них связь рвалась на каждой
         * паузе между переходами по каталогам.
         */
        const val TIMEOUT = 60_000L
    }
}
