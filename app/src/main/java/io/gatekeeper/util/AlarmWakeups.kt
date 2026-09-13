package io.gatekeeper.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Разовое пробуждение через AlarmManager. Нужно там, где интент надо доставить из
 * фонового процесса: прямой `startActivity` оттуда рубит Background Activity
 * Launch, а сработавший будильник такое право дает.
 *
 * Раньше эти две функции были скопированы слово в слово в ProfileNotifier и
 * CrossProfileScheduler; третий потребитель (сторож новых пакетов, F3) стал
 * поводом свести их в одно место.
 */
object AlarmWakeups {

    fun activity(context: Context, intent: Intent, requestCode: Int, delayMs: Long): Boolean {
        val pi = PendingIntents.activity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return schedule(context, pi, delayMs)
    }

    fun broadcast(context: Context, intent: Intent, requestCode: Int, delayMs: Long): Boolean {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return schedule(context, pi, delayMs)
    }

    private fun schedule(context: Context, pi: PendingIntent, delayMs: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
        return true
    }
}
