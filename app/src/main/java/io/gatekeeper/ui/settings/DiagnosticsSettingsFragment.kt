package io.gatekeeper.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.PowerDiagnostics
import io.gatekeeper.util.Utility

/**
 * Экран «Диагностика»: семь проверок окружения отдельными строками с вердиктом,
 * у проблемной -- кнопка в тот системный экран, где это чинится. Раньше все семь
 * лежали в одном абзаце диалога, из которого не было видно, что именно не так.
 */
class DiagnosticsSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_diagnostics)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Опрос рабочего профиля -- синхронный биндер, поэтому не на UI-потоке. */
    private fun refresh() {
        val appContext = requireContext().applicationContext
        val workService = serviceWork
        Thread {
            val snapshot = collectPowerDiagnostics(appContext, workService)
            postOnUi { fillChecks(snapshot) }
        }.start()
    }

    private fun fillChecks(snapshot: PowerDiagnostics.Snapshot) {
        val category = findPreference<PreferenceCategory>(SETTINGS_DIAG_LIST) ?: return
        category.removeAll()
        for (check in checks(snapshot)) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = getString(check.titleRes)
                    summary = verdict(check)
                    isIconSpaceReserved = false
                    isSelectable = check.fix != null && check.status == check.badWhen
                    setOnPreferenceClickListener {
                        check.fix?.invoke()
                        true
                    }
                }
            )
        }
    }

    private fun verdict(check: Check): CharSequence = when (check.status) {
        PowerDiagnostics.Status.UNKNOWN -> getString(R.string.power_diagnostics_unknown)
        check.badWhen -> tinted(getString(check.badRes), R.attr.colorWarning)
        else -> getString(check.okRes)
    }

    private fun checks(snapshot: PowerDiagnostics.Snapshot): List<Check> = listOf(
        Check(
            R.string.settings_diag_battery_main,
            snapshot.mainIgnoringBatteryOptimizations,
            PowerDiagnostics.Status.NO,
            R.string.settings_diag_battery_main_bad,
            R.string.settings_diag_ok,
        ) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        Check(
            R.string.settings_diag_battery_work,
            snapshot.workIgnoringBatteryOptimizations,
            PowerDiagnostics.Status.NO,
            R.string.settings_diag_battery_work_bad,
            R.string.settings_diag_ok,
        ) { openWorkPowerSettings() },
        Check(
            R.string.settings_diag_background_main,
            snapshot.mainBackgroundRestricted,
            PowerDiagnostics.Status.YES,
            R.string.settings_diag_background_main_bad,
            R.string.settings_diag_ok,
        ) { openSettings(appDetailsIntent()) },
        Check(
            R.string.settings_diag_background_work,
            snapshot.workBackgroundRestricted,
            PowerDiagnostics.Status.YES,
            R.string.settings_diag_background_work_bad,
            R.string.settings_diag_ok,
        ) { openWorkPowerSettings() },
        Check(
            R.string.settings_diag_power_save,
            snapshot.powerSaveMode,
            PowerDiagnostics.Status.YES,
            R.string.settings_diag_power_save_bad,
            R.string.settings_diag_ok,
        ) { openSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
        Check(
            R.string.settings_diag_doze,
            snapshot.deviceIdleMode,
            PowerDiagnostics.Status.YES,
            R.string.settings_diag_doze_bad,
            R.string.settings_diag_doze_ok,
            fix = null,
        ),
        Check(
            R.string.settings_diag_work_service,
            snapshot.workServiceAlive,
            PowerDiagnostics.Status.NO,
            R.string.settings_diag_work_service_bad,
            R.string.settings_diag_work_service_ok,
            fix = null,
        ),
    )

    private fun collectPowerDiagnostics(
        context: Context,
        workService: IGatekeeperService?,
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

    /** Одна проверка: вердикт зависит от того, какой ответ здесь считается плохим. */
    private class Check(
        val titleRes: Int,
        val status: PowerDiagnostics.Status,
        val badWhen: PowerDiagnostics.Status,
        val badRes: Int,
        val okRes: Int,
        val fix: (() -> Unit)?,
    )

    companion object {
        private const val SETTINGS_DIAG_LIST = "settings_diag_list"
    }
}
