package io.gatekeeper.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.gatekeeper.R
import io.gatekeeper.util.Utility

/**
 * Экран «Доступы»: семь разрешений из четырёх мест запроса -- одним списком,
 * с фактом выдачи и с кнопкой в нужный системный экран. Сводка честная: без
 * каждого из них конкретная функция мертва, и это написано прямо в строке.
 */
class PermissionsSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_permissions)
    }

    override fun onResume() {
        super.onResume()
        fillPermissions()
    }

    private fun fillPermissions() {
        val category = findPreference<PreferenceCategory>(SETTINGS_PERM_LIST) ?: return
        category.removeAll()
        permissionEntries().forEach { entry ->
            val pref = Preference(requireContext()).apply {
                key = entry.key
                title = getString(entry.titleRes)
                summary = statusText(entry)
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    openSettings(entry.intent())
                    true
                }
            }
            category.addPreference(pref)
        }
    }

    private fun statusText(entry: PermEntry): String = if (entry.granted()) {
        getString(R.string.settings_perm_granted)
    } else {
        getString(R.string.settings_perm_missing, getString(entry.reasonRes))
    }

    private data class PermEntry(
        val key: String,
        val titleRes: Int,
        val reasonRes: Int,
        val granted: () -> Boolean,
        val intent: () -> Intent,
    )

    private fun permissionEntries(): List<PermEntry> = buildList {
        addAll(notificationPermissionEntries())
        addAll(corePermissionEntries())
    }

    private fun notificationPermissionEntries(): List<PermEntry> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermEntry(
                    key = "perm_notifications",
                    reasonRes = R.string.settings_perm_reason_notifications,
                    titleRes = R.string.settings_perm_notifications,
                    granted = {
                        ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    },
                    intent = {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                        }
                    },
                )
            )
        }
    }

    private fun corePermissionEntries(): List<PermEntry> = buildList {
        add(
            PermEntry(
                key = "perm_all_files",
                reasonRes = R.string.settings_perm_reason_all_files,
                titleRes = R.string.settings_perm_all_files,
                granted = { allFilesGranted() },
                intent = { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) },
            )
        )
        add(
            PermEntry(
                key = "perm_usage_stats",
                reasonRes = R.string.settings_perm_reason_usage_stats,
                titleRes = R.string.settings_perm_usage_stats,
                granted = { usageStatsGranted() },
                intent = { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            )
        )
        addAll(packageAccessEntries())
        add(
            PermEntry(
                key = "perm_battery",
                reasonRes = R.string.settings_perm_reason_battery,
                titleRes = R.string.settings_perm_battery,
                granted = {
                    requireContext().getSystemService(PowerManager::class.java)
                        ?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
                },
                intent = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
            )
        )
        add(
            PermEntry(
                key = "perm_vpn",
                reasonRes = R.string.settings_perm_reason_vpn,
                titleRes = R.string.settings_perm_vpn,
                granted = { VpnService.prepare(requireContext()) == null },
                intent = { VpnService.prepare(requireContext()) ?: Intent() },
            )
        )
    }

    private fun packageAccessEntries(): List<PermEntry> = buildList {
        add(
            PermEntry(
                key = "perm_overlay",
                reasonRes = R.string.settings_perm_reason_overlay,
                titleRes = R.string.settings_perm_overlay,
                granted = { Utility.checkSystemAlertPermission(requireContext()) },
                intent = {
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}"),
                    )
                },
            )
        )
        add(
            PermEntry(
                key = "perm_install_unknown",
                reasonRes = R.string.settings_perm_reason_install_unknown,
                titleRes = R.string.settings_perm_install_unknown,
                granted = {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        requireContext().packageManager.canRequestPackageInstalls()
                },
                intent = {
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${requireContext().packageName}"),
                    )
                },
            )
        )
    }

    private fun allFilesGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val workGranted = try {
            serviceWork?.hasAllFileAccessPermission()
        } catch (_: RemoteException) {
            false
        }
        return Utility.checkAllFileAccessPermission() && workGranted == true
    }

    private fun usageStatsGranted(): Boolean {
        val workGranted = try {
            serviceWork?.hasUsageStatsPermission()
        } catch (_: RemoteException) {
            false
        }
        return Utility.checkUsageStatsPermission(requireContext()) && workGranted == true
    }

    companion object {
        private const val SETTINGS_PERM_LIST = "settings_perm_list"
    }
}
