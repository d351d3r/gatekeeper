package io.gatekeeper.util

import io.gatekeeper.R

/**
 * Почему не удалось получить сервис рабочего профиля и что делать дальше (D3).
 *
 * Вынесено из `MainActivity` без зависимости от Android SDK: это решение определяет, увидит
 * пользователь объяснение или пустой экран, и проверяется JVM-тестами.
 */
object WorkServiceBindFailure {
    /** Биндер получен, обрабатывать нечего. */
    const val NONE = 0

    /** Результата нет: activity в профиле закрылась сама или ее убили по дороге. */
    const val CANCELLED = 1

    /** Результат пришел, биндера в нем нет: реле дошло, сервис не поднялся. */
    const val NO_BINDER = 2

    /** Реле не резолвится: профиль выключен, снесен или в нем нет приложения. */
    const val NO_RESOLUTION = 3

    /** Сколько раз повторяем молча, прежде чем спросить пользователя. */
    const val SILENT_RETRIES = 1

    fun classify(resultOk: Boolean, hasBinder: Boolean): Int = when {
        !resultOk -> CANCELLED
        !hasBinder -> NO_BINDER
        else -> NONE
    }

    fun messageOf(reason: Int): Int = when (reason) {
        CANCELLED -> R.string.work_service_bind_failed_cancelled
        NO_BINDER -> R.string.work_service_bind_failed_no_binder
        NO_RESOLUTION -> R.string.work_service_bind_failed_no_resolution
        else -> 0
    }

    fun shouldRetrySilently(attemptsMade: Int): Boolean = attemptsMade < SILENT_RETRIES
}
