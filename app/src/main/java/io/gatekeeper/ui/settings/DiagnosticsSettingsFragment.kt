package io.gatekeeper.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.PowerDiagnostics
import io.gatekeeper.util.Utility

/** Экран «Диагностика»: семь проверок окружения с кнопками в системные настройки. */
class DiagnosticsSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_diagnostics)
        findPreference<Preference>(SETTINGS_POWER_DIAGNOSTICS)
            ?.setOnPreferenceClickListener { showPowerDiagnostics() }
    }

    private fun showPowerDiagnostics(): Boolean {
        val appContext = requireContext().applicationContext
        val workService = serviceWork
        Thread {
            val snapshot = collectPowerDiagnostics(appContext, workService)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_power_diagnostics)
                    .setMessage(
                        getString(
                            R.string.settings_power_diagnostics_message,
                            statusTitle(snapshot.mainIgnoringBatteryOptimizations),
                            statusTitle(snapshot.workIgnoringBatteryOptimizations),
                            statusTitle(snapshot.mainBackgroundRestricted),
                            statusTitle(snapshot.workBackgroundRestricted),
                            statusTitle(snapshot.powerSaveMode),
                            statusTitle(snapshot.deviceIdleMode),
                            statusTitle(snapshot.workServiceAlive),
                        )
                    )
                    .setPositiveButton(R.string.power_diagnostics_open_battery) { _, _ ->
                        openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    .setNeutralButton(R.string.power_diagnostics_open_app) { _, _ ->
                        openSettings(appDetailsIntent())
                    }
                    .setNegativeButton(R.string.power_diagnostics_open_work) { _, _ ->
                        openWorkPowerSettings()
                    }
                    .show()
            }
        }.start()
        return true
    }

    private fun collectPowerDiagnostics(
        context: Context,
        workService: io.gatekeeper.services.IGatekeeperService?,
    ): PowerDiagnostics.Snapshot {
        val local = runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val activity = context.getSystemService(ActivityManager::class.java)
            PowerDiagnostics.ProfileSignals(
                ignoringBatteryOptimizations =
                    power?.isIgnoringBatteryOptimizations(context.packageName) ?: return@runCatching null,
                backgroundRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        (activity?.isBackgroundRestricted ?: return@runCatching null),
            )
        }.getOrNull()
        val work = workService?.let { service ->
            runCatching {
                PowerDiagnostics.ProfileSignals(
                    ignoringBatteryOptimizations = service.isIgnoringBatteryOptimizations(),
                    backgroundRestricted = service.isBackgroundRestricted(),
                )
            }.getOrNull()
        }
        val power = context.getSystemService(PowerManager::class.java)
        return PowerDiagnostics.collect(
            mainSignals = local,
            workSignals = work,
            powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull(),
            deviceIdleMode = runCatching { power?.isDeviceIdleMode }.getOrNull(),
            workServiceAlive = workService?.asBinder()?.isBinderAlive,
        )
    }

    private fun statusTitle(status: PowerDiagnostics.Status): String = getString(
        when (status) {
            PowerDiagnostics.Status.YES -> R.string.power_diagnostics_yes
            PowerDiagnostics.Status.NO -> R.string.power_diagnostics_no
            PowerDiagnostics.Status.UNKNOWN -> R.string.power_diagnostics_unknown
        }
    )

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }

    private fun openWorkPowerSettings() {
        val intent = Intent(io.gatekeeper.ui.DummyActivity.OPEN_POWER_SETTINGS)
        if (!Utility.tryTransferIntentToProfile(requireContext(), intent)) {
            GatekeeperToast.show(requireContext(), R.string.power_diagnostics_work_unavailable)
            return
        }
        openSettings(intent)
    }

    companion object {
        private const val SETTINGS_POWER_DIAGNOSTICS = "settings_power_diagnostics"
    }
}
