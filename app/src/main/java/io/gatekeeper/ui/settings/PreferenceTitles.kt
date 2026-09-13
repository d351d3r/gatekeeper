package io.gatekeeper.ui.settings

import androidx.preference.Preference
import androidx.preference.PreferenceGroup

/**
 * Заголовки строк настроек в одну строку по умолчанию, и длинные обрезаются
 * многоточием: «Заморозка при блокировке экр..». Названия функций у нас длиннее
 * одной строки, потому что называют функцию, а не категорию.
 */
object PreferenceTitles {

    fun allowMultiline(group: PreferenceGroup) {
        group.isSingleLineTitle = false
        for (i in 0 until group.preferenceCount) {
            when (val child: Preference = group.getPreference(i)) {
                is PreferenceGroup -> allowMultiline(child)
                else -> child.isSingleLineTitle = false
            }
        }
    }
}
