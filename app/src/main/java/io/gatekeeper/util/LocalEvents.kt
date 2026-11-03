package io.gatekeeper.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Внутрипроцессная шина событий вместо androidx LocalBroadcastManager: библиотека
 * объявлена устаревшей, а заменять ее предлагают «наблюдаемыми» компонентами, которые
 * для пяти событий экрана -- лишний слой.
 *
 * Семантика та же, что у LocalBroadcastManager, чтобы замена была механической:
 * - получатели живут только в этом процессе (между основным процессом и `:vpnwatch`
 *   шина не работает -- как и LBM; туда ходят пакетные broadcast'ы);
 * - доставка асинхронная, на главном потоке, в порядке регистрации;
 * - получатель, снятый до доставки, событие уже не получит;
 * - в onReceive приходит контекст приложения.
 */
object LocalEvents {
    private const val TAG = "LocalEvents"

    private class Registration(val receiver: BroadcastReceiver, val filter: IntentFilter)

    private val registrations = CopyOnWriteArrayList<Registration>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        registrations.add(Registration(receiver, filter))
    }

    /** Снять все регистрации получателя. Повторный вызов и чужой получатель -- не ошибка. */
    fun unregister(receiver: BroadcastReceiver) {
        registrations.removeAll { it.receiver === receiver }
    }

    /** @return true, если нашелся хотя бы один получатель. */
    fun send(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext
        val type = intent.resolveTypeIfNeeded(app.contentResolver)
        val targets = registrations.filter {
            it.filter.match(intent.action, type, intent.scheme, intent.data, intent.categories, TAG) >= 0
        }
        if (targets.isEmpty()) return false
        mainHandler.post {
            for (target in targets) {
                if (registrations.contains(target)) {
                    target.receiver.onReceive(app, intent)
                }
            }
        }
        return true
    }
}
