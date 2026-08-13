package io.gatekeeper.util

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.gatekeeper.services.FileShuttleService
import io.gatekeeper.services.IFileShuttleService
import io.gatekeeper.services.IFileShuttleServiceCallback
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.FileShuttleAuthActivity
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Связь личного профиля с файловым шаттлом другого профиля.
 *
 * Начиная с API 26 привязку инициирует видимая [FileShuttleAuthActivity] по действию
 * клиента SAF, а [CrossProfileDocumentsProvider] сюда только заглядывает: запуск activity
 * из провайдера -- фоновый старт, который система блокирует, а ожидание его результата и
 * давало `ContentProvider not responding`.
 *
 * Установленная связь живет сессию: провайдер отмечает работу пользователя [touch], а пинги
 * не дают удаленному сервису покончить с собой. Через [SESSION_IDLE_MS] без работы пинги
 * прекращаются, и другой профиль отпускается.
 */
object FileShuttleConnection {
    private const val TAG = "FileShuttleConnection"

    /** Ожидание биндера в [FileShuttleAuthActivity]; измеренный сквозной путь -- ~300 мс. */
    const val BIND_TIMEOUT_MS = 6_000L

    /** Потолок на один вызов шаттла. Система объявляет провайдер зависшим через 20 с. */
    const val CALL_TIMEOUT_MS = 10_000L

    /**
     * Сколько связь живет после последней работы пользователя с файлами. Держать дольше
     * бессмысленно: удерживаемый процесс живет в другом профиле, и прошивки вроде ColorOS
     * замораживают его тем охотнее, чем дольше он висит без дела.
     */
    const val SESSION_IDLE_MS = 5 * 60_000L

    /**
     * Потолок на сессию целиком, сколько бы ее ни продлевали. Фоновый клиент с сохраненным
     * tree-URI, опрашивающий провайдер по расписанию, иначе удерживал бы передний план в
     * другом профиле бесконечно -- в приложении, которое существует ради заморозки этого
     * профиля.
     */
    const val SESSION_MAX_MS = 30 * 60_000L

    /** Период удерживающих пингов: удаленный сервис умирает через [FileShuttleService.TIMEOUT] простоя. */
    const val PING_INTERVAL_MS = FileShuttleService.TIMEOUT / 2

    private const val REQUEST_CODE_AUTH = 0xF11E

    /**
     * Вызовы шаттла уходят с потока провайдера: транзакция к другому профилю синхронная,
     * и живой, но не отвечающий процесс иначе заблокировал бы поток до
     * `ContentProvider not responding`.
     */
    private val calls: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "file-shuttle-call").apply { isDaemon = true }
    }

    /**
     * Пинги идут отдельным потоком: удерживать связь надо и тогда, когда поток провайдера
     * занят вызовом.
     */
    private val pings: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "file-shuttle-ping").apply { isDaemon = true }
        }

    private val pingLock = Object()
    private var scheduledPing: ScheduledFuture<*>? = null

    /**
     * Удаленный сервис убивает себя после [FileShuttleService.TIMEOUT] простоя, но пока
     * сессия жива, его удерживают пинги. Локальный срок годности -- сессия работы с
     * файлами, а не таймаут шаттла: иначе связь рвется на каждой паузе между каталогами.
     * Отдельный срок по последней дошедшей отметке нужен потому, что пинги не идут, пока
     * наш собственный процесс заморожен: после такой паузы другая сторона уже могла умереть,
     * и звонить туда нельзя.
     */
    private val session = ShuttleSession<IFileShuttleService>(
        SESSION_IDLE_MS,
        FileShuttleService.TIMEOUT,
        SESSION_MAX_MS,
        BIND_TIMEOUT_MS,
        SystemClock::elapsedRealtime
    )

    @Volatile
    private var lastQueriedDocumentId: String? = null

    /** Нужен, чтобы уведомить наблюдателей из колбэка привязки: он приходит на биндер-поток. */
    @Volatile
    private var appContext: Context? = null

    fun peek(): IFileShuttleService? = session.peek { it.asBinder().isBinderAlive }

    /**
     * Отмечает работу пользователя с файлами и запускает удержание связи. Активность --
     * только запрос клиента SAF: пинги ходят через [peek] и сессию не продлевают, иначе
     * процесс в другом профиле удерживался бы вечно.
     */
    fun touch() {
        session.touch()
        if (peek() != null) schedulePing()
    }

    fun rememberQueriedDocument(documentId: String) {
        lastQueriedDocumentId = documentId
    }

    /**
     * Единственный способ позвать шаттл. Возвращает null, если связи нет, биндер умер,
     * вызов не удался или другой профиль не ответил за [CALL_TIMEOUT_MS]. Поток
     * вызывающего освобождается в любом случае; поток-исполнитель прервать нельзя --
     * бинарная транзакция не прерывается, и он остается занят до ответа другой стороны.
     * Число таких потоков ограничено заявками, поданными до первого таймаута: после него
     * биндер сброшен и новые вызовы отсекает [peek].
     *
     * Классу исключения верить нельзя: [DeadObjectException] приходит и на смерть процесса,
     * и на любую неудавшуюся транзакцию с коротким запросом (`signalExceptionForError`
     * отдает [android.os.TransactionTooLargeException] только для отправленного parcel
     * больше 200 КБ, а запрос шаттла -- это всегда короткий путь). Единственный надежный
     * признак смерти -- флаг транспорта: `BpBinder` гасит его только на `DEAD_OBJECT`.
     */
    fun <T : Any> call(block: (IFileShuttleService) -> T?): T? {
        val service = peek() ?: return null
        val sentAt = SystemClock.elapsedRealtime()
        val abandoned = AtomicBoolean(false)
        val future = try {
            CompletableFuture.supplyAsync({ block(service) }, calls).whenComplete { result, _ ->
                // Поздний ответ уже никому не нужен: без явного закрытия дескриптор
                // доживет до финализатора.
                if (abandoned.get() && result is ParcelFileDescriptor) {
                    try {
                        result.close()
                    } catch (e: IOException) {
                        Log.w(TAG, "cannot close an abandoned descriptor", e)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "file shuttle call rejected", e)
            return null
        }
        return try {
            future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).also { session.markContact(sentAt) }
        } catch (e: TimeoutException) {
            abandoned.set(true)
            Log.w(TAG, "file shuttle did not answer in $CALL_TIMEOUT_MS ms")
            session.drop(service)
            null
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is RemoteException -> {
                    Log.w(TAG, "file shuttle call failed", cause)
                    if (!service.asBinder().isBinderAlive) session.drop(service)
                }
                is RuntimeException -> throw cause
                else -> throw RuntimeException(cause)
            }
            null
        } catch (e: InterruptedException) {
            abandoned.set(true)
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Запрашивает привязку. [context] обязан быть видимой activity везде, где система
     * применяет запрет фонового старта, то есть на всех версиях, где у клиента SAF вообще
     * есть действие «подключиться». Возвращает false, только если запрос не удалось даже
     * отправить -- другого профиля нет, он выключен или реле не резолвится.
     */
    fun requestBind(context: Context): Boolean {
        appContext = context.applicationContext
        if (peek() != null) return true
        val generation = session.beginBind()
        if (generation == ShuttleSession.NO_GENERATION) return true

        val intent = Intent(DummyActivity.START_FILE_SHUTTLE)
        intent.putExtra("extra", Bundle().apply {
            putBinder("callback", object : IFileShuttleServiceCallback.Stub() {
                override fun callback(service: IFileShuttleService) {
                    onBinderArrived(generation, service)
                }
            })
        })
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            try {
                Utility.transferIntentToProfile(context, intent)
            } catch (e: IllegalStateException) {
                intent.action = DummyActivity.START_FILE_SHUTTLE_2
                Utility.transferIntentToProfile(context, intent)
            }
            context.startActivity(intent)
            true
        } catch (e: IllegalStateException) {
            bindFailed(generation, e)
            false
        } catch (e: ActivityNotFoundException) {
            bindFailed(generation, e)
            false
        } catch (e: SecurityException) {
            bindFailed(generation, e)
            false
        }
    }

    fun awaitBinder(timeoutMs: Long): IFileShuttleService? = session.await(timeoutMs)

    fun release() {
        session.release()
        synchronized(pingLock) {
            scheduledPing?.cancel(false)
            scheduledPing = null
        }
    }

    /**
     * Один такт удержания. Сессия истекла или биндер мертв -- цикл прекращается, и
     * удаленный сервис умирает сам через [FileShuttleService.TIMEOUT] простоя.
     *
     * Пинг асинхронный (см. AIDL): если передний план на той стороне не удержался и процесс
     * все-таки заморожен, синхронный пинг стоил бы ему жизни, а асинхронный просто ляжет в
     * буфер до разморозки.
     */
    private val pingTask: Runnable = Runnable {
        if (peek() != null) {
            call<Unit> { it.ping() }
            schedulePing()
        }
    }

    private fun schedulePing(): Unit = synchronized(pingLock) {
        scheduledPing?.cancel(false)
        scheduledPing = pings.schedule(pingTask, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /** Подставляет биндер без кросс-профильной привязки: она требует двух профилей. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun injectForTest(service: IFileShuttleService) {
        session.release()
        session.accept(session.beginBind(), service)
    }

    /**
     * На пути с [android.app.AuthenticationRequiredException] курсор не возвращается,
     * подписки на URI у клиента нет, и список перечитывается по результату
     * [FileShuttleAuthActivity]. Уведомление нужно тем, кто курсор получил: это клиенты
     * ниже API 26, где провайдер отдает пустой курсор с ошибкой.
     */
    private fun notifyChange(context: Context) {
        val resolver = context.contentResolver
        resolver.notifyChange(
            DocumentsContract.buildRootsUri(CrossProfileDocumentsProvider.AUTHORITY),
            null
        )
        lastQueriedDocumentId?.let {
            resolver.notifyChange(
                DocumentsContract.buildDocumentUri(CrossProfileDocumentsProvider.AUTHORITY, it),
                null
            )
        }
    }

    /**
     * Действие для клиента SAF. [PendingIntent.FLAG_IMMUTABLE] обязателен: без него
     * получатель допишет в интент свои параметры и запустит нашу activity с ними.
     */
    fun authPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_AUTH,
        Intent(context, FileShuttleAuthActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun bindFailed(generation: Int, cause: Exception) {
        Log.w(TAG, "cannot reach the other profile", cause)
        session.failBind(generation)
    }

    private fun onBinderArrived(generation: Int, service: IFileShuttleService) {
        if (!session.accept(generation, service)) {
            Log.i(TAG, "ignoring binder from a stale bind attempt")
            return
        }
        schedulePing()
        appContext?.let { notifyChange(it) }
        // Без подписки на смерть BpBinder о ней не узнает и isBinderAlive продолжит врать.
        try {
            service.asBinder().linkToDeath({ session.discard(generation) }, 0)
        } catch (e: RemoteException) {
            session.discard(generation)
        }
    }
}
