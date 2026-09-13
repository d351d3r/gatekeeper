package io.gatekeeper.ui.settings

import android.os.Bundle
import io.gatekeeper.R

/**
 * Экран «Удаление Gatekeeper»: что защищено, что уносит профиль и что сделать до.
 *
 * Замерено на AVD 16 (plan.md, F5-bis). Пока Gatekeeper -- владелец профиля,
 * система сама отказывается удалять его рабочую копию: `pm uninstall` отвечает
 * DELETE_FAILED_DEVICE_POLICY_MANAGER и для user 11, и для всех пользователей
 * сразу, а в системных настройках профиля кнопка «Удалить» гасится. Поэтому
 * DevicePolicyManager.setUninstallBlocked на самого себя тут не нужен -- он
 * закрывал бы уже закрытую дверь. Профиль уносит другая, системная: «Удалить
 * рабочий профиль» в настройках аккаунтов. Ее приложение убрать не может.
 */
class RemovalSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_removal)
    }
}
