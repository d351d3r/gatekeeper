package io.gatekeeper.util

import android.content.Intent

/**
 * Что личная сторона согласна запустить по просьбе сервиса рабочего профиля.
 *
 * Сервис профиля не может стартовать activity из фона, поэтому просит видимую личную
 * MainActivity: та переадресует интент через форвардер обратно в профиль. Раньше прокси
 * запускал любой интент с любыми флагами от вызывающего -- держатель биндера получал
 * «запусти от имени Gatekeeper что угодно, в том числе с выдачей URI-прав» (S1).
 *
 * Без Android SDK в логике: флаги и действия -- константы.
 */
object StartActivityProxyPolicy {
    /** Сервис профиля просит только установку и удаление пакета. */
    val ALLOWED_ACTIONS: Set<String> = setOf(ProfileActions.INSTALL_PACKAGE, ProfileActions.UNINSTALL_PACKAGE)

    fun isAllowed(action: String?): Boolean = action in ALLOWED_ACTIONS

    /**
     * Оставляет только флаги, которые ставит сам сервис: новая задача и, для установки
     * из APK, право чтения файла. Запись, «навсегда» и префиксные гранты отбрасываются.
     */
    fun sanitizeFlags(action: String?, flags: Int): Int {
        var allowed = Intent.FLAG_ACTIVITY_NEW_TASK
        if (action == ProfileActions.INSTALL_PACKAGE) {
            allowed = allowed or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        return flags and allowed
    }
}
