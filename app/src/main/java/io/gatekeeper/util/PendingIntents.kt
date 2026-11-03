package io.gatekeeper.util

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Фаза 13, п.6: PendingIntent.getActivity, отправляемый из фона (будильник,
 * PackageInstaller), на targetSdk 34+ по умолчанию BAL-блокируется, если создатель
 * не разрешил это явно (замер Фазы 19 на AOSP 16: "balRequireOptInByPendingIntentCreator",
 * доставка статуса установки молча умирала). Все такие PendingIntent создаются
 * только через этот хелпер.
 */
object PendingIntents {
    fun activity(
        context: Context,
        requestCode: Int,
        intent: Intent,
        flags: Int,
    ): PendingIntent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return PendingIntent.getActivity(context, requestCode, intent, flags)
        }
        val options = ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(creatorMode(Build.VERSION.SDK_INT))
        }
        return PendingIntent.getActivity(
            context, requestCode, intent, flags, options.toBundle()
        )
    }

    /**
     * Создатель отдает свои права на запуск из фона: отправитель -- система (будильник,
     * PackageInstaller), а она за нас их не добавит. С API 36 для этого есть
     * MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS; старый MODE_..._ALLOWED Android 17
     * просит не использовать. До API 36 другого значения нет.
     */
    @SuppressLint("InlinedApi") // версия проверяется по параметру (ради теста); константы -- int
    fun creatorMode(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION") // единственное значение до API 36
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
}
