package io.gatekeeper.util

import androidx.appcompat.app.AppCompatDelegate

/**
 * Выбор темы: системная (по умолчанию), светлая, тёмная. Значение хранится в
 * LocalStorageManager, применяется через AppCompatDelegate на старте приложения и
 * при смене в настройках. Сама палитра остаётся DayNight/Material3.
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun apply(value: String?) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun current(): String =
        LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_THEME_MODE) ?: SYSTEM
}
