package io.gatekeeper.ui.settings

import android.os.Bundle
import io.gatekeeper.R

/**
 * Экран «Удаление приложения»: цена удаления названа заранее, а не после.
 *
 * Рабочий профиль живет ровно столько, сколько живет его владелец: снимаешь
 * Gatekeeper -- система сносит профиль со всеми клонами и их данными. Перехватить
 * удаление приложение не может, поэтому единственная честная мера -- сказать об
 * этом там, где пользователь ищет удаление, и показать, что сделать до него.
 */
class RemovalSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_removal)
    }
}
