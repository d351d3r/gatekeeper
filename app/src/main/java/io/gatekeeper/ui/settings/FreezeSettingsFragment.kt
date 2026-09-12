package io.gatekeeper.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.Utility

/** Экран «Заморозка»: автозаморозка по блокировке, задержка, пакетные действия, ярлыки. */
class FreezeSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_freeze)
        bindAutoFreeze()
        bindDelay()
        bindSkipForeground()
        bindBatchActions()
    }

    private fun bindAutoFreeze() {
        val pref = findPreference<CheckBoxPreference>(SETTINGS_AUTO_FREEZE_SERVICE) ?: return
        pref.isChecked = manager.getAutoFreezeServiceEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setAutoFreezeServiceEnabled(newState as Boolean)
            true
        }
    }

    private fun bindDelay() {
        val pref = findPreference<DropDownPreference>(SETTINGS_AUTO_FREEZE_DELAY) ?: return
        pref.entries = AUTO_FREEZE_DELAY_SECONDS
            .map { getString(R.string.format_minutes, it / SECONDS_IN_MINUTE) }
            .toTypedArray()
        pref.entryValues = AUTO_FREEZE_DELAY_SECONDS
            .map { it.toString() }
            .toTypedArray()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setAutoFreezeDelay((newState as String).toInt())
            updateDelaySummary()
            true
        }
        updateDelaySummary()
    }

    private fun updateDelaySummary() {
        findPreference<DropDownPreference>(SETTINGS_AUTO_FREEZE_DELAY)?.summary =
            getString(R.string.format_minutes, manager.getAutoFreezeDelay() / SECONDS_IN_MINUTE)
    }

    private fun bindSkipForeground() {
        val pref = findPreference<CheckBoxPreference>(SETTINGS_SKIP_FOREGROUND) ?: return
        pref.isChecked = manager.getSkipForegroundEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            onSkipForegroundChange(newState as Boolean)
        }
    }

    private fun onSkipForegroundChange(enabled: Boolean): Boolean {
        val granted = !enabled || usageStatsGranted()
        if (!granted) return false
        manager.setSkipForegroundEnabled(enabled)
        return true
    }

    private fun usageStatsGranted(): Boolean = ensureSpecialAccessPermission(
        checkPermission = {
            try {
                serviceWork?.hasUsageStatsPermission() == true &&
                    Utility.checkUsageStatsPermission(requireContext())
            } catch (_: RemoteException) {
                false
            }
        },
        alertRes = R.string.request_usage_stats,
        settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
    )

    private fun bindBatchActions() {
        findPreference<Preference>(SETTINGS_UNFREEZE_ALL)
            ?.setOnPreferenceClickListener { startBatch(DummyActivity.PUBLIC_UNFREEZE_ALL) }
        findPreference<Preference>(SETTINGS_FREEZE_ALL)
            ?.setOnPreferenceClickListener { startBatch(DummyActivity.PUBLIC_FREEZE_ALL) }
        findPreference<Preference>(SETTINGS_CREATE_FREEZE_ALL_SHORTCUT)
            ?.setOnPreferenceClickListener {
                createShortcut(
                    DummyActivity.PUBLIC_FREEZE_ALL,
                    "gatekeeper-freeze-all",
                    R.drawable.ic_shortcut_freeze,
                    R.string.freeze_all_shortcut,
                )
            }
        findPreference<Preference>(SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT)
            ?.setOnPreferenceClickListener {
                createShortcut(
                    DummyActivity.PUBLIC_UNFREEZE_ALL,
                    "gatekeeper-unfreeze-all",
                    R.drawable.ic_shortcut_unfreeze,
                    R.string.unfreeze_all_shortcut,
                )
            }
    }

    private fun startBatch(action: String): Boolean {
        val intent = Intent(action).apply {
            component = ComponentName(requireContext(), DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(intent)
        startActivity(intent)
        return true
    }

    private fun createShortcut(action: String, id: String, iconRes: Int, titleRes: Int): Boolean {
        val launchIntent = Intent(requireContext(), DummyActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        Utility.createLauncherShortcut(
            requireContext(), launchIntent,
            Utility.createBatchShortcutIcon(requireContext(), iconRes),
            id, getString(titleRes),
        )
        return true
    }

    companion object {
        private const val SETTINGS_AUTO_FREEZE_SERVICE = "settings_auto_freeze_service"
        private const val SETTINGS_AUTO_FREEZE_DELAY = "settings_auto_freeze_delay"
        private const val SETTINGS_SKIP_FOREGROUND = "settings_dont_freeze_foreground"
        private const val SETTINGS_FREEZE_ALL = "settings_freeze_all"
        private const val SETTINGS_UNFREEZE_ALL = "settings_unfreeze_all"
        private const val SETTINGS_CREATE_FREEZE_ALL_SHORTCUT = "settings_create_freeze_all_shortcut"
        private const val SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT = "settings_create_unfreeze_all_shortcut"

        private const val SECONDS_IN_MINUTE = 60
        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}
