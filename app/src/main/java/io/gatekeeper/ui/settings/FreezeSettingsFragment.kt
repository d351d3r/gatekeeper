package io.gatekeeper.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ScreenLockFreezeScope
import io.gatekeeper.util.Utility

/**
 * Экран «Заморозка»: автозаморозка по блокировке, задержка, пакетные действия,
 * ярлыки и список автозаморозки целиком -- в настройках, а не по одной
 * снежинке в списке приложений.
 */
class FreezeSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_freeze)
        bindCheckBox(
            SETTINGS_AUTO_FREEZE_SERVICE,
            manager.getAutoFreezeServiceEnabled(),
        ) { enabled ->
            manager.setAutoFreezeServiceEnabled(enabled)
            true
        }
        bindCheckBox(
            SETTINGS_SKIP_FOREGROUND,
            manager.getSkipForegroundEnabled(),
            this::onSkipForegroundChange,
        )
        bindScope()
        bindDelay()
        bindBatchActions()
    }

    override fun onResume() {
        super.onResume()
        fillAutoFreezeList()
    }

    private fun bindScope() {
        val pref = findPreference<DropDownPreference>(SETTINGS_AUTO_FREEZE_SCOPE) ?: return
        val titles = mapOf(
            ScreenLockFreezeScope.SESSION to R.string.settings_auto_freeze_scope_session,
            ScreenLockFreezeScope.AUTO_FREEZE_LIST to R.string.settings_auto_freeze_scope_list,
            ScreenLockFreezeScope.WHOLE_WORK_PROFILE to R.string.settings_auto_freeze_scope_all,
        )
        pref.entries = ScreenLockFreezeScope.entries
            .map { getString(titles.getValue(it)) }
            .toTypedArray()
        pref.entryValues = ScreenLockFreezeScope.entries
            .map { it.stored.toString() }
            .toTypedArray()
        pref.value = manager.getAutoFreezeScope().stored.toString()
        // «Пропускать активные» смотрит статистику использования -- она есть только у
        // сеансовой области, для списков решение принимает WorkProfileBatchFreeze.
        val skipForeground = findPreference<Preference>(SETTINGS_SKIP_FOREGROUND)
        skipForeground?.isEnabled =
            manager.getAutoFreezeScope() == ScreenLockFreezeScope.SESSION
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setAutoFreezeScope(
                ScreenLockFreezeScope.fromStored((newState as String).toInt())
            )
            skipForeground?.isEnabled =
                manager.getAutoFreezeScope() == ScreenLockFreezeScope.SESSION
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
            pref.summary = getString(
                R.string.format_minutes,
                manager.getAutoFreezeDelay() / SECONDS_IN_MINUTE,
            )
            true
        }
        pref.summary = getString(
            R.string.format_minutes,
            manager.getAutoFreezeDelay() / SECONDS_IN_MINUTE,
        )
    }

    private fun onSkipForegroundChange(enabled: Boolean): Boolean {
        val granted = !enabled || ensureSpecialAccessPermission(
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
        if (!granted) return false
        manager.setSkipForegroundEnabled(enabled)
        return true
    }

    private fun bindBatchActions() {
        findPreference<Preference>(SETTINGS_UNFREEZE_ALL)?.setOnPreferenceClickListener {
            startBatch(DummyActivity.PUBLIC_UNFREEZE_ALL)
        }
        findPreference<Preference>(SETTINGS_FREEZE_ALL)?.setOnPreferenceClickListener {
            startBatch(DummyActivity.PUBLIC_FREEZE_ALL)
        }
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

    /** Список автозаморозки целиком: метки подтягиваем сервисом рабочего профиля. */
    fun fillAutoFreezeList() {
        val category = findPreference<PreferenceCategory>(SETTINGS_AUTO_FREEZE_LIST) ?: return
        val packages = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
            .toSet()
        val work = serviceWork
        if (packages.isEmpty() || work == null) {
            category.removeAll()
            val empty = Preference(requireContext()).apply {
                title = getString(R.string.settings_auto_freeze_list_empty)
                isEnabled = false
                isIconSpaceReserved = false
            }
            category.addPreference(empty)
            return
        }
        Thread {
            val apps = fetchApps(work)
            postOnUi { showAutoFreezeEntries(category, packages, apps) }
        }.start()
    }

    private fun showAutoFreezeEntries(
        category: PreferenceCategory,
        packages: Set<String>,
        apps: List<io.gatekeeper.util.ApplicationInfoWrapper>?,
    ) {
        category.removeAll()
        val labels = apps?.associate { it.getPackageName() to it.getLabel() }
        packages.sortedBy { labels?.get(it) ?: it }.forEach { pkg ->
            val pref = Preference(requireContext()).apply {
                title = labels?.get(pkg) ?: pkg
                summary = pkg
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    confirmRemove(this@FreezeSettingsFragment, pkg, title.toString())
                    true
                }
            }
            category.addPreference(pref)
        }
    }

    companion object {
        private const val SETTINGS_AUTO_FREEZE_SERVICE = "settings_auto_freeze_service"
        private const val SETTINGS_AUTO_FREEZE_SCOPE = "settings_auto_freeze_scope"
        private const val SETTINGS_AUTO_FREEZE_DELAY = "settings_auto_freeze_delay"
        private const val SETTINGS_SKIP_FOREGROUND = "settings_dont_freeze_foreground"
        private const val SETTINGS_FREEZE_ALL = "settings_freeze_all"
        private const val SETTINGS_UNFREEZE_ALL = "settings_unfreeze_all"
        private const val SETTINGS_CREATE_FREEZE_ALL_SHORTCUT = "settings_create_freeze_all_shortcut"
        private const val SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT = "settings_create_unfreeze_all_shortcut"
        private const val SETTINGS_AUTO_FREEZE_LIST = "settings_auto_freeze_list"

        private const val SECONDS_IN_MINUTE = 60
        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}

/** Диалог удаления из списка автозаморозки; вынесен из класса, чтобы экран не разрастался. */
private fun confirmRemove(fragment: FreezeSettingsFragment, pkg: String, label: String) {
    AlertDialog.Builder(fragment.requireContext())
        .setTitle(R.string.settings_auto_freeze_remove_title)
        .setMessage(fragment.getString(R.string.settings_auto_freeze_remove_message, label))
        .setPositiveButton(R.string.ca_remove_action) { _, _ ->
            val local = LocalStorageManager.getInstance()
            val remaining = local
                .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                .filterNot { it == pkg }
            local.setStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                remaining.toTypedArray(),
            )
            Utility.scheduleAppListRefresh(fragment.requireContext())
            fragment.fillAutoFreezeList()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
