package io.gatekeeper.util

import java.io.File
import java.io.IOException

/**
 * Контракт документ-Id File Shuttle (E-7): клиенты ходят только через
 * DUMMY_ROOT-префикс (прямые вызовы провайдера) или уже развёрнутые
 * абсолютные пути внутри общего внешнего хранилища (round-trip DocumentsUI).
 *
 * Всё остальное — относительные пути, выход за корень через ".." или
 * симлинки, неканонизируемые пути — отклоняется null'ом. Вызывающий обязан
 * вернуть нейтральный отказ, а не клампить путь в корень: молчаливый кламп
 * превращал ошибку клиента (замер: listed=13 parsed=0) в операцию над
 * корнем хранилища чужого профиля. Исключение из AIDL-стаба не вариант —
 * унесло бы процесс профиля.
 */
object FileShuttlePathResolver {

    /**
     * @return канонический абсолютный путь внутри [root] или null при отказе.
     */
    fun resolve(path: String, dummyRoot: String, root: File): String? {
        if (path.isEmpty()) return null
        val f = if (path.startsWith(dummyRoot)) {
            File(root, path.substring(dummyRoot.length))
        } else {
            File(path)
        }
        // Корень канонизируем тоже: у него может быть симлинк в пути
        // (macOS /tmp -> /private/tmp, sdcard -> /storage/emulated/0).
        val rootPath = try {
            root.canonicalPath
        } catch (_: IOException) {
            root.absolutePath
        }
        return canonicalize(f)
            ?.takeIf { it.absolutePath == rootPath || it.absolutePath.startsWith("$rootPath/") }
            ?.absolutePath
    }

    private fun canonicalize(f: File): File? =
        try {
            f.canonicalFile
        } catch (_: IOException) {
            null
        }
}
