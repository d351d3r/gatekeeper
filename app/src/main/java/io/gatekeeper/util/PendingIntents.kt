package io.gatekeeper.util

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
            // Constant deprecated in API 35, but it is still the mode this API 34+ setter takes.
            @Suppress("DEPRECATION")
            setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        return PendingIntent.getActivity(
            context, requestCode, intent, flags, options.toBundle()
        )
    }
}
