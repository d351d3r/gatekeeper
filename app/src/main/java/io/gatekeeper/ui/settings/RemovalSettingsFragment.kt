package io.gatekeeper.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.ui.PanicWipeActivity

/**
 * Экран «Удаление Gatekeeper»: что защищено, что уносит профиль и что сделать до.
 * Строка «Стереть рабочий профиль» открывает то же подтверждение, что и плитка
 * быстрых настроек ([PanicWipeActivity]) -- удаление необратимо, поэтому гейтится им.
 *
 * Замерено на AVD 16 (plan.md, F5-bis). Пока Gatekeeper -- владелец профиля,
 * система сама отказывается удалять его рабочую копию: `pm uninstall` отвечает
 * DELETE_FAILED_DEVICE_POLICY_MANAGER и для user 11, и для всех пользователей
 * сразу, а в системных настройках профиля кнопка «Удалить» гасится. Само удаление
 * профиля теперь делает сам Gatekeeper через DevicePolicyManager.wipeData.
 */
class RemovalSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_removal)
        findPreference<Preference>(SETTINGS_REMOVAL_WIPE)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PanicWipeActivity::class.java))
            true
        }
    }

    private companion object {
        private const val SETTINGS_REMOVAL_WIPE = "settings_removal_wipe"
    }
}
