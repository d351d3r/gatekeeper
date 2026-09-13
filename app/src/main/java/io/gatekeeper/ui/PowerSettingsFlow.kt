package io.gatekeeper.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.gatekeeper.R
import io.gatekeeper.util.GatekeeperToast

/**
 * Диагностика питания: OPEN_POWER_SETTINGS открывает карточку приложения
 * в настройках, при её отсутствии — экран игнорирования оптимизаций
 * батареи, при повторном отказе — тост. Вынесено из DummyActivity.
 */
class PowerSettingsFlow(private val activity: DummyActivity) {

    fun handleOpenPowerSettings() {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        try {
            activity.startActivity(details)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                GatekeeperToast.show(activity, R.string.power_diagnostics_settings_unavailable)
            }
        }
        activity.finish()
    }
}
