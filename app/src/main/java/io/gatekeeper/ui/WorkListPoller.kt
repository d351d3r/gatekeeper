package io.gatekeeper.ui

import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.Lifecycle
import io.gatekeeper.services.IGetAppsCallback
import io.gatekeeper.util.ApplicationInfoWrapper

/**
 * Периодический опрос списка приложений рабочего профиля, пока активность
 * видна: ловит установку/удаление (RuStore и пр.) без ручного pull-to-refresh.
 * Вынесен из MainActivity; живёт и умирает вместе с ней.
 *
 * Жизненный цикл: [start]/[stop] из onResume/onPause активности. Внутри
 * колбэка биндера проверяем RESUMED ещё раз: callback может прилететь
 * после onPause, и без проверки поллер возродился бы мёртвым.
 */
class WorkListPoller(
    private val activity: MainActivity,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { poll() }
    private var snapshot: Set<String>? = null

    fun start() {
        handler.removeCallbacks(runnable)
        if (!resumed() || !activity.servicesAlive()) {
            return
        }
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
        snapshot = null
    }

    private fun resumed(): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** Detect RuStore / work-profile installs while the user stays on the work tab. */
    private fun poll() {
        if (!resumed()) {
            stop()
            return
        }
        val work = activity.workServiceOrNull()
        if (work == null || !activity.servicesAlive()) {
            handler.postDelayed(runnable, intervalMs)
            return
        }
        try {
            work.getApps(object : IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<ApplicationInfoWrapper>) {
                    if (!resumed()) {
                        return
                    }
                    val current = apps.map { it.getPackageName() }.toSet()
                    val previous = snapshot
                    if (previous != null && previous != current) {
                        Log.i(
                            TAG,
                            "work profile app set changed (${previous.size} -> ${current.size}), refreshing",
                        )
                        activity.runOnUiThread { activity.refreshAppLists() }
                    }
                    snapshot = current
                    if (resumed()) {
                        handler.postDelayed(runnable, intervalMs)
                    }
                }
            }, activity.showAll)
        } catch (_: RemoteException) {
            if (resumed()) {
                handler.postDelayed(runnable, intervalMs)
            }
        }
    }

    private companion object {
        private const val TAG = "WorkListPoller"
        private const val DEFAULT_INTERVAL_MS = 2000L
    }
}
