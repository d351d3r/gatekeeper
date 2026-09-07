package io.gatekeeper.util

/**
 * Объяснимые исходы клонирования/установки (Фаза 19: надёжное клонирование).
 *
 * Коды результата приходят из двух мест:
 *  - собственные коды [CloneOutcome] (отказы, которые Gatekeeper обнаружил сам,
 *    до запуска PackageInstaller);
 *  - PackageInstaller: RESULT_OK при успехе, иначе RESULT_FIRST_USER + STATUS_…
 *    (см. PackageInstallerSession.commit: статус доставляется через PendingIntent
 *    как resultCode).
 *
 * Числа Activity.RESULT_OK = -1, RESULT_CANCELED = 0, RESULT_FIRST_USER = 1 и
 * PackageInstaller.STATUS_* = 0..6 зафиксированы платформой, поэтому расшифровка
 * не требует android-импортов и покрыта JVM-тестами.
 */
object CloneOutcome {
    /** Системное приложение недоступно в профиле-приёмнике (или профиль не наш). */
    const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001

    /** Пакет уже установлен в профиле-приёмнике (в том числе заморожен/скрыт). */
    const val RESULT_ALREADY_IN_PROFILE = 100002

    /** Сервис профиля-приёмника недоступен (RemoteException на вызывающей стороне). */
    const val RESULT_NO_PROFILE_CONNECTION = 100003

    // PackageInstaller.STATUS_* (платформа, зафиксировано)
    private const val STATUS_FAILURE_ABORTED = 1
    private const val STATUS_FAILURE_BLOCKED = 2
    private const val STATUS_FAILURE_CONFLICT = 3
    private const val STATUS_FAILURE_INCOMPATIBLE = 4
    private const val STATUS_FAILURE_INVALID = 5
    private const val STATUS_FAILURE_STORAGE = 6

    // Activity.RESULT_* (платформа, зафиксировано)
    private const val ACTIVITY_RESULT_OK = -1
    private const val ACTIVITY_RESULT_CANCELED = 0
    private const val ACTIVITY_RESULT_FIRST_USER = 1

    enum class Reason {
        SUCCESS,
        ALREADY_IN_PROFILE,
        SYSTEM_APP_UNAVAILABLE,
        NO_PROFILE_CONNECTION,
        CANCELLED_BY_USER,
        BLOCKED,
        CONFLICT,
        INCOMPATIBLE,
        INVALID_APK,
        OUT_OF_SPACE,
        ABORTED,
        UNKNOWN,
    }

    fun reasonOf(resultCode: Int): Reason = when (resultCode) {
        ACTIVITY_RESULT_OK -> Reason.SUCCESS
        RESULT_CANNOT_INSTALL_SYSTEM_APP -> Reason.SYSTEM_APP_UNAVAILABLE
        RESULT_ALREADY_IN_PROFILE -> Reason.ALREADY_IN_PROFILE
        RESULT_NO_PROFILE_CONNECTION -> Reason.NO_PROFILE_CONNECTION
        ACTIVITY_RESULT_CANCELED -> Reason.CANCELLED_BY_USER
        else -> when (resultCode - ACTIVITY_RESULT_FIRST_USER) {
            STATUS_FAILURE_ABORTED -> Reason.ABORTED
            STATUS_FAILURE_BLOCKED -> Reason.BLOCKED
            STATUS_FAILURE_CONFLICT -> Reason.CONFLICT
            STATUS_FAILURE_INCOMPATIBLE -> Reason.INCOMPATIBLE
            STATUS_FAILURE_INVALID -> Reason.INVALID_APK
            STATUS_FAILURE_STORAGE -> Reason.OUT_OF_SPACE
            else -> Reason.UNKNOWN
        }
    }
}
