package io.gatekeeper

import android.app.AuthenticationRequiredException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Point
import android.database.Cursor
import android.os.Build
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.TransactionTooLargeException
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.services.FileShuttleService
import io.gatekeeper.services.IFileShuttleService
import io.gatekeeper.util.CrossProfileDocumentsProvider
import io.gatekeeper.util.FileShuttleConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Провайдер SAF без живой связи с шаттлом. Ключевое требование: ни один путь не ждет
 * дольше [DEADLINE_MS] и не может привести к `ContentProvider not responding`, даже если
 * другой профиль не отвечает вовсе.
 *
 * Тесты намеренно не требуют рабочего профиля: проверяется поведение при недоступном
 * шаттле, а оно должно быть одинаковым и когда профиля нет, и когда он выключен.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsProviderTest {
    private val context get() = TestProfiles.targetContext
    private val resolver get() = context.contentResolver

    private val providerComponent
        get() = ComponentName(context, CrossProfileDocumentsProvider::class.java)

    private var previousComponentState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Before
    fun enableProviderAndDropShuttle() {
        // Компонент включается галкой File Shuttle; тест не должен от нее зависеть.
        previousComponentState = context.packageManager.getComponentEnabledSetting(providerComponent)
        setProviderState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        // Прогон идет в процессе приложения, поэтому состояние связи -- то же самое.
        FileShuttleConnection.release()
    }

    @After
    fun restoreProviderState() {
        FileShuttleConnection.release()
        setProviderState(previousComponentState)
    }

    private fun setProviderState(state: Int) {
        context.packageManager.setComponentEnabledSetting(
            providerComponent, state, PackageManager.DONT_KILL_APP
        )
    }

    @Test
    fun queryRoots_worksWithoutShuttle() {
        val cursor = failFast { resolver.query(DocumentsContract.buildRootsUri(AUTHORITY), null, null, null) }
        assertNotNull("queryRoots вернул null", cursor)
        cursor!!.use { assertEquals(1, it.count) }
    }

    /**
     * DocumentsUI резолвит корень через `GetRootDocumentTask`, который
     * `AuthenticationRequiredException` не понимает: без синтетического ответа вместо
     * кнопки рисуется «Can't load content at the moment».
     */
    @Test
    fun queryDocument_forRoot_isSynthetic_withoutShuttle() {
        val cursor = failFast {
            resolver.query(DocumentsContract.buildDocumentUri(AUTHORITY, ROOT), null, null, null)
        }
        assertNotNull("queryDocument для корня вернул null", cursor)
        cursor!!.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(ROOT, it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)))
            assertEquals(
                DocumentsContract.Document.MIME_TYPE_DIR,
                it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            )
        }
    }

    @Test
    fun queryChildDocuments_reportsActionInsteadOfHanging() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val thrown = thrownBy {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                ?.close()
        }
        assertTrue(
            "ожидалось AuthenticationRequiredException, получено $thrown",
            thrown is AuthenticationRequiredException
        )
        assertNotNull(
            "в исключении нет действия для пользователя",
            (thrown as AuthenticationRequiredException).userAction
        )
    }

    /**
     * Без [android.app.PendingIntent.FLAG_IMMUTABLE] любой клиент SAF допишет в интент
     * свои параметры и запустит нашу activity с ними.
     */
    @Test
    fun authPendingIntentIsImmutable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val thrown = thrownBy {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                ?.close()
        }
        assertTrue(thrown is AuthenticationRequiredException)
        assertTrue(
            "PendingIntent должен быть неизменяемым",
            (thrown as AuthenticationRequiredException).userAction.isImmutable
        )
    }

    @Test
    fun openDocument_throwsFileNotFound_whenShuttleUnavailable() {
        val thrown = thrownBy {
            resolver.openFileDescriptor(
                DocumentsContract.buildDocumentUri(AUTHORITY, ROOT + "no_such_file.txt"), "r"
            )?.close()
        }
        assertTrue("ожидалось FileNotFoundException, получено $thrown", thrown is FileNotFoundException)
    }

    /**
     * Биндер живой, а другой профиль не отвечает: транзакция к нему синхронная, и без
     * потолка по времени она держала бы поток провайдера до
     * `ContentProvider not responding`.
     */
    @Test
    fun queryChildDocuments_returnsWhenShuttleIsAliveButSilent() {
        val blocked = CountDownLatch(1)
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun loadFiles(path: String): List<*> {
                blocked.await()
                return emptyList<Any>()
            }
        })
        try {
            val thrown = thrownBy(FileShuttleConnection.CALL_TIMEOUT_MS + DEADLINE_MS) {
                resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                    ?.close()
            }
            assertTrue(
                "ожидалось действие вместо залипания, получено $thrown",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O || thrown is AuthenticationRequiredException
            )
        } finally {
            blocked.countDown()
        }
    }

    /**
     * Отказ конкретного вызова (например, `TransactionTooLargeException` на большом
     * каталоге) -- не потеря связи. Предлагать в этом случае «подключиться» нельзя:
     * подключение удастся, отказ повторится, и пользователь окажется в замкнутом круге.
     */
    @Test
    fun queryChildDocuments_keepsConnection_whenCallItselfFails() {
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun loadFiles(path: String): List<*> = throw TransactionTooLargeException()
        })
        val cursor = failFast {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
        }
        assertNotNull("ожидался курсор с ошибкой, а не исключение", cursor)
        cursor!!.use {
            assertEquals(0, it.count)
            assertEquals(
                context.getString(R.string.file_shuttle_read_failed),
                it.extras.getString(DocumentsContract.EXTRA_ERROR)
            )
        }
        assertNotNull("живая связь не должна рваться из-за отказа вызова", FileShuttleConnection.peek())
    }

    /**
     * Переполнение буфера бинарных транзакций приходит клиенту тем же классом, что и смерть
     * процесса: `signalExceptionForError` отдает [TransactionTooLargeException] только для
     * **отправленного** parcel больше 200 КБ, а запрос шаттла -- это всегда короткий путь,
     * поэтому слишком большой **ответ** (`loadFiles` каталога на тысячи файлов) выглядит как
     * [DeadObjectException]. Если верить классу исключения, живая связь выбрасывается и
     * пользователь попадает в круг «подключиться -> тот же отказ».
     */
    @Test
    fun keepsConnection_whenTransactionFailsButBinderIsAlive() {
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun loadFiles(path: String): List<*> = throw DeadObjectException()
        })
        val cursor = failFast {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
        }
        assertNotNull("ожидался курсор с ошибкой, а не предложение подключиться", cursor)
        cursor!!.use {
            assertEquals(
                context.getString(R.string.file_shuttle_read_failed),
                it.extras.getString(DocumentsContract.EXTRA_ERROR)
            )
        }
        assertNotNull(
            "связь жива (биндер не помечен мертвым) и рвать ее нельзя",
            FileShuttleConnection.peek()
        )
    }

    /**
     * Вызывается платформенным `enforceTree` до тела остальных методов, поэтому без связи
     * клиент с tree-URI должен получить действие, а не «документ не потомок».
     */
    @Test
    fun isChildDocument_reportsActionInsteadOfDenying() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val thrown = thrownBy {
            DocumentsContract.isChildDocument(
                resolver,
                DocumentsContract.buildDocumentUri(AUTHORITY, ROOT),
                DocumentsContract.buildDocumentUri(AUTHORITY, ROOT + "Download")
            )
        }
        assertTrue(
            "ожидалось AuthenticationRequiredException, получено $thrown",
            thrown is AuthenticationRequiredException
        )
    }

    /**
     * Связь жива, отказал сам вызов. Бросать отсюда нельзя: `enforceTree` проверяемых
     * исключений не объявляет, и клиент с tree-URI получит пустой список без объяснения
     * вместо ответа предиката.
     */
    @Test
    fun isChildDocument_deniesWithoutThrowing_whenCallItselfFails() {
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun isChildOf(parentPath: String, childPath: String): Boolean =
                throw TransactionTooLargeException()
        })
        val result = AtomicReference<Boolean?>()
        val thrown = thrownBy {
            result.set(
                DocumentsContract.isChildDocument(
                    resolver,
                    DocumentsContract.buildDocumentUri(AUTHORITY, ROOT),
                    DocumentsContract.buildDocumentUri(AUTHORITY, ROOT + "Download")
                )
            )
        }
        assertNull("предикат навигации не должен бросать при живой связи", thrown)
        assertEquals(false, result.get())
        assertNotNull("живая связь не должна рваться из-за отказа вызова", FileShuttleConnection.peek())
    }

    /**
     * D2: переход в соседний каталог после паузы обязан идти по уже установленной связи.
     * Локальная ссылка жила `FileShuttleService.TIMEOUT / 2`, то есть 5 с простоя, и любая
     * пауза длиннее возвращала пользователя к запросу подключения.
     */
    @Test
    fun connectionSurvivesPauseBetweenDirectories() {
        FileShuttleConnection.injectForTest(StubShuttle())
        failFast { resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null) }?.close()

        Thread.sleep(PAUSE_MS)

        val thrown = thrownBy {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                ?.close()
        }
        assertNull("после паузы $PAUSE_MS мс снова требуется подключение: $thrown", thrown)
        assertNotNull("связь не должна рваться за паузу между переходами", FileShuttleConnection.peek())
    }

    /**
     * Длительность собственного вызова не должна старить сессию: отметка активности
     * ставилась в начале вызова, и следующий запрос выбрасывал живой биндер по сроку
     * годности, хотя связь все это время работала.
     */
    @Test
    fun longCallDoesNotExpireLiveConnection() {
        val first = AtomicBoolean(true)
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun loadFiles(path: String): List<*> {
                if (first.getAndSet(false)) Thread.sleep(LONG_CALL_MS)
                return emptyList<Any>()
            }
        })
        val slow = thrownBy(LONG_CALL_MS + DEADLINE_MS) {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                ?.close()
        }
        assertNull("долгий вызов не должен отказывать: $slow", slow)

        val thrown = thrownBy {
            resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                ?.close()
        }
        assertNull("живой биндер выброшен из-за длительности предыдущего вызова: $thrown", thrown)
        assertNotNull("живая связь не должна рваться", FileShuttleConnection.peek())
    }

    /**
     * Удаленный сервис убивает себя после [FileShuttleService.TIMEOUT] простоя. Пока
     * пользователь работает с файлами, его обязаны удерживать пинги: иначе каждая пауза
     * упирается в новый запрос подключения, а на устройстве пользователя -- еще и в BAL.
     */
    @Test
    fun shuttleIsPingedBeforeItSuicides() {
        val pings = AtomicInteger()
        FileShuttleConnection.injectForTest(object : StubShuttle() {
            override fun ping() {
                pings.incrementAndGet()
            }
        })
        failFast { resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null) }?.close()

        val quietMs = FileShuttleService.TIMEOUT / 2 + 5_000L
        Thread.sleep(quietMs)

        assertTrue(
            "за $quietMs мс шаттл не получил ни одного пинга и умрет через " +
                    "${FileShuttleService.TIMEOUT} мс простоя",
            pings.get() >= 1
        )
    }

    @Test
    fun concurrentQueries_doNotDeadlock() {
        val threads = 8
        val started = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { i ->
            Thread({
                started.countDown()
                try {
                    if (i % 2 == 0) {
                        resolver.query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT), null, null, null)
                            ?.close()
                    } else {
                        resolver.query(DocumentsContract.buildDocumentUri(AUTHORITY, ROOT), null, null, null)
                            ?.close()
                    }
                } catch (_: Throwable) {
                    // содержательные проверки -- в отдельных тестах, здесь важно только отсутствие залипания
                } finally {
                    done.countDown()
                }
            }, "saf-concurrent-$i").apply { isDaemon = true }.start()
        }
        started.await(DEADLINE_MS, TimeUnit.MILLISECONDS)
        assertTrue(
            "параллельные запросы не завершились за $DEADLINE_MS мс",
            done.await(DEADLINE_MS, TimeUnit.MILLISECONDS)
        )
    }

    /** Выполняет запрос на отдельном потоке и падает, если он не уложился в дедлайн. */
    private fun failFast(block: () -> Cursor?): Cursor? {
        val result = AtomicReference<Cursor?>()
        val error = AtomicReference<Throwable?>()
        runWithDeadline {
            try {
                result.set(block())
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        error.get()?.let { throw it }
        return result.get()
    }

    /** Возвращает исключение, брошенное запросом, или null. Дедлайн обязателен. */
    private fun thrownBy(deadlineMs: Long = DEADLINE_MS, block: () -> Unit): Throwable? {
        val error = AtomicReference<Throwable?>()
        runWithDeadline(deadlineMs) {
            try {
                block()
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        return error.get()
    }

    private fun runWithDeadline(deadlineMs: Long = DEADLINE_MS, block: () -> Unit) {
        val done = CountDownLatch(1)
        Thread({
            try {
                block()
            } finally {
                done.countDown()
            }
        }, "saf-deadline").apply { isDaemon = true }.start()
        assertTrue(
            "вызов провайдера не вернулся за $deadlineMs мс -- это путь к ANR",
            done.await(deadlineMs, TimeUnit.MILLISECONDS)
        )
    }

    /** Заглушка шаттла: тесты переопределяют только тот метод, который проверяют. */
    private open class StubShuttle : IFileShuttleService.Stub() {
        override fun ping() = Unit
        override fun loadFiles(path: String): List<*> = emptyList<Any>()
        override fun loadFileMeta(path: String): Map<*, *> = emptyMap<Any, Any>()
        override fun openFile(path: String, mode: String): ParcelFileDescriptor? = null
        override fun openThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? = null
        override fun createFile(path: String, mimeType: String, displayName: String): String? = null
        override fun deleteFile(path: String): String = path
        override fun isChildOf(parentPath: String, childPath: String) = false
    }

    companion object {
        private const val DEADLINE_MS = 6_000L

        /** Пауза между переходами по каталогам; дольше прежнего срока годности связи (5 с). */
        private const val PAUSE_MS = 7_000L

        /** Долгий, но укладывающийся в [FileShuttleConnection.CALL_TIMEOUT_MS] вызов. */
        private const val LONG_CALL_MS = 7_000L
        private val AUTHORITY = BuildConfig.APPLICATION_ID + ".documents"
        private const val ROOT = CrossProfileDocumentsProvider.DUMMY_ROOT
    }
}
