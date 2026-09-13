package io.gatekeeper.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.Lifecycle
import io.gatekeeper.util.PackageSequence

/**
 * Слежение за составом приложений рабочего профиля, пока активность на экране:
 * ловит установку и удаление (RuStore и прочие магазины внутри профиля) без
 * ручного pull-to-refresh. Живет и умирает вместе с активностью.
 *
 * Спрашиваем не список, а водяной знак [PackageSequence]: ответ «ничего не
 * менялось» стоит одной транзакции. Перечисление всего списка стоило трех IPC на
 * каждый пакет (launch intent, метка, скрытость) и держало занятым чужой профиль
 * -- тот самый, который прошивки замораживают тем охотнее, чем больше он
 * шевелится. Замер на AVD 16, 235 пакетов: 0.89 с CPU за 20 с, около 4.5% ядра
 * непрерывно.
 */
class WorkListPoller(
    private val activity: MainActivity,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { poll() }
    private var probeThread: HandlerThread? = null
    private var probe: Handler? = null

    /**
     * Номер последовательности не сбрасывается паузой: первый запрос с нуля тянет
     * через границу профиля список всего, что менялось с загрузки, а последующие --
     * пустой ответ. Читается и пишется только с UI-потока.
     */
    private var sequence: Int? = null

    /**
     * Состояние жизненного цикла проверяет [poll], а не этот метод: внутри onResume
     * androidx-состояние еще STARTED (см. `MainActivity.showWorkServiceBindFailed`),
     * и синхронная проверка здесь гасила слежение навсегда после первого возврата
     * в приложение -- замерено: 0 тиков CPU у профиля вместо ожидаемого опроса.
     */
    fun start() {
        handler.removeCallbacks(runnable)
        if (probeThread == null) {
            val thread = HandlerThread(PROBE_THREAD).apply { start() }
            probeThread = thread
            probe = Handler(thread.looper)
        }
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
        probe = null
        probeThread?.quit()
        probeThread = null
    }

    private fun resumed(): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /**
     * Транзакция уходит с отдельного потока: она идет в процесс другого профиля,
     * и живой, но не отвечающий процесс иначе задержал бы UI-поток.
     */
    private fun poll() {
        if (!resumed()) {
            stop()
            return
        }
        val work = activity.workServiceOrNull()
        val worker = probe
        if (work == null || worker == null || !activity.servicesAlive()) {
            handler.postDelayed(runnable, intervalMs)
            return
        }
        val since = sequence ?: PackageSequence.FROM_SCRATCH
        worker.post {
            val current = try {
                work.getPackageChangeSequence(since)
            } catch (_: RemoteException) {
                PROBE_FAILED
            }
            handler.post { onProbeResult(current) }
        }
    }

    private fun onProbeResult(current: Int) {
        // Ответ мог обогнать stop(): без этой проверки он перезавел бы цикл.
        if (probe == null || !resumed()) {
            return
        }
        when (current) {
            PROBE_FAILED -> Unit
            PackageSequence.UNSUPPORTED -> {
                // До Android 8 водяного знака нет; остается pull-to-refresh.
                Log.i(TAG, "package change watermark unavailable, watching off")
                stop()
                return
            }
            else -> {
                if (PackageSequence.changed(sequence, current)) {
                    Log.i(TAG, "work profile packages changed ($sequence -> $current), refreshing")
                    activity.refreshAppLists()
                }
                sequence = current
            }
        }
        handler.postDelayed(runnable, intervalMs)
    }

    private companion object {
        private const val TAG = "WorkListPoller"
        private const val PROBE_THREAD = "work-list-probe"
        private const val PROBE_FAILED = Int.MIN_VALUE
        private const val DEFAULT_INTERVAL_MS = 2000L
    }
}
