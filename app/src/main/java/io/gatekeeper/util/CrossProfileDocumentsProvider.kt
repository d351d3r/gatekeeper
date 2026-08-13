package io.gatekeeper.util

import android.app.AuthenticationRequiredException
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import java.io.FileNotFoundException
import java.io.Serializable

// A document provider to show files across the profile boundary
// in the system's Documents UI.
// This is an interface to FileShuttleService
class CrossProfileDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    // Отметки активности тут нет: корень отвечается локально, а перечисляет корни любое
    // приложение, открывающее выбор файлов, -- это не работа с файлами другого профиля.
    override fun queryRoots(projection: Array<String>?): Cursor {
        val context = providerContext()
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher_zindan)
        row.add(
            DocumentsContract.Root.COLUMN_TITLE,
            if (Utility.isProfileOwner(context)) {
                context.getString(R.string.fragment_profile_main)
            } else {
                context.getString(R.string.fragment_profile_work)
            }
        )
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                    or DocumentsContract.Root.FLAG_LOCAL_ONLY
                    or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        // Корень резолвится клиентом до всего остального и по пути, который
        // AuthenticationRequiredException не понимает: DocumentsUI рисует вместо действия
        // общую заглушку "Can't load content at the moment". Отвечаем синтетически.
        if (documentId == DUMMY_ROOT) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, providerContext().getString(R.string.app_name))
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L)
            return result
        }

        FileShuttleConnection.touch()
        @Suppress("UNCHECKED_CAST")
        val fileInfo = FileShuttleConnection.call {
            it.loadFileMeta(documentId) as Map<String, Serializable>
        } ?: return unavailable(projection)
        includeFile(result, fileInfo)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        FileShuttleConnection.touch()
        FileShuttleConnection.rememberQueriedDocument(parentDocumentId)
        @Suppress("UNCHECKED_CAST")
        val files = FileShuttleConnection.call {
            it.loadFiles(parentDocumentId) as List<Map<String, Serializable>>
        } ?: return unavailable(projection, parentDocumentId)
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        result.setNotificationUri(
            providerContext().contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
        )

        for (file in files) {
            includeFile(result, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        FileShuttleConnection.touch()
        return FileShuttleConnection.call { it.openFile(documentId, mode) }
            ?: throw FileNotFoundException(documentId)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        FileShuttleConnection.touch()
        val thumbnail = FileShuttleConnection.call { it.openThumbnail(documentId, sizeHint) }
            ?: throw FileNotFoundException(documentId)
        return AssetFileDescriptor(thumbnail, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        FileShuttleConnection.touch()
        // Пустая строка отличает "шаттл не ответил" от "шаттл ответил отказом".
        val created = FileShuttleConnection.call {
            it.createFile(parentDocumentId, mimeType, displayName) ?: ""
        } ?: authRequired()
        if (created.isEmpty()) {
            throw FileNotFoundException("$parentDocumentId/$displayName")
        }
        providerContext().contentResolver.notifyChange(
            DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId),
            null
        )
        return created
    }

    override fun deleteDocument(documentId: String) {
        FileShuttleConnection.touch()
        val parent = FileShuttleConnection.call { it.deleteFile(documentId) } ?: authRequired()
        providerContext().contentResolver.notifyChange(
            DocumentsContract.buildDocumentUri(AUTHORITY, parent),
            null
        )
    }

    // Зовется платформенным DocumentsProvider.enforceTree раньше тела остальных методов:
    // отдать здесь false -- значит сказать клиенту с tree-URI "не потомок" вместо действия.
    // Действие уместно только при мертвой связи: enforceTree проверяемых исключений не
    // объявляет, и брошенное отсюда FileNotFoundException дойдет до клиента пустым списком
    // без объяснения. Ниже API 26 и при отказе живого вызова остается false.
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        FileShuttleConnection.touch()
        val isChild = FileShuttleConnection.call { it.isChildOf(parentDocumentId, documentId) }
        if (isChild == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && FileShuttleConnection.peek() == null) {
            authRequired()
        }
        return isChild ?: false
    }

    /**
     * Ответ клиенту SAF, когда данных нет: с API 26 и мертвой связью -- штатное действие
     * "подключиться", иначе -- пустой курсор с текстом ошибки.
     */
    private fun unavailable(projection: Array<String>?, notifyFor: String? = null): Cursor {
        val context = providerContext()
        val connected = FileShuttleConnection.peek() != null
        if (!connected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                authRequired()
            }
            // До API 26 у клиента SAF действия нет, зато фоновый старт activity система еще
            // разрешает: единственная точка входа в привязку -- сам провайдер. Ожидания тут
            // по-прежнему нет, курсор уходит сразу, а список перечитается по notifyChange
            // из колбэка привязки.
            FileShuttleConnection.requestBind(context)
        }
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.extras = Bundle().apply {
            putString(
                DocumentsContract.EXTRA_ERROR,
                context.getString(
                    if (connected) R.string.file_shuttle_read_failed else R.string.file_shuttle_unavailable
                )
            )
        }
        if (notifyFor != null) {
            cursor.setNotificationUri(
                context.contentResolver,
                DocumentsContract.buildDocumentUri(AUTHORITY, notifyFor)
            )
        }
        return cursor
    }

    private fun authRequired(): Nothing {
        val context = providerContext()
        // Связь жива -- значит отказал сам вызов, и повторное подключение приведет ровно
        // к тому же отказу. Предлагать в этом случае действие -- замкнутый круг.
        if (FileShuttleConnection.peek() != null) {
            throw FileNotFoundException(context.getString(R.string.file_shuttle_read_failed))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw FileNotFoundException(context.getString(R.string.file_shuttle_unavailable))
        }
        throw AuthenticationRequiredException(
            IllegalStateException(NO_SHUTTLE),
            FileShuttleConnection.authPendingIntent(context)
        )
    }

    private fun providerContext() = requireNotNull(context) { "provider used before onCreate" }

    private fun includeFile(cursor: MatrixCursor, fileInfo: Map<String, Serializable>) {
        val row = cursor.newRow()
        for (col in DEFAULT_DOCUMENT_PROJECTION) {
            row.add(col, fileInfo[col])
        }
    }

    companion object {
        const val DUMMY_ROOT = "/shelter_storage_root/"
        val AUTHORITY = BuildConfig.APPLICATION_ID + ".documents"
        private const val NO_SHUTTLE = "file shuttle is not connected"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
