package io.gatekeeper.util

/**
 * Фаза 17 (D8): отбор файлов для автопереноса из рабочего профиля в личний.
 * docs/feature_media_mirror.md: область строго ограничена (Screenshots и камера),
 * только изображения и видео, разумный потолок размера, догон по watermark
 * ("файлы, появившиеся при мертвой связи, подбираются при следующем подключении").
 * Без android-импортов -- покрыто JVM-тестами.
 */
object MediaMirrorSelection {
    /** Копируем, не перемещаем (docs: дублирование места -- честная цена); потолок на файл. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** Каталоги-источники внутри рабочего профиля; всё остальное -- не уезжает никогда. */
    val WATCHED_DIRS = listOf("Pictures/Screenshots", "DCIM/Camera")

    data class RemoteFile(
        val path: String,
        val name: String,
        val mime: String?,
        val size: Long,
        val lastModified: Long,
    )

    fun isImageOrVideo(mime: String?): Boolean =
        mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

    /** Новый = изменён позже watermark; порядок по времени делает watermark монотонным. */
    fun select(files: List<RemoteFile>, watermarkMs: Long): List<RemoteFile> =
        files.filter { file ->
            isImageOrVideo(file.mime) &&
                file.size in 1..MAX_FILE_BYTES &&
                file.lastModified > watermarkMs
        }.sortedBy { it.lastModified }
}
