package io.gatekeeper.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.gatekeeper.R
import io.gatekeeper.services.ILoadIconCallback
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ScreenLockFreezeScope
import io.gatekeeper.util.ProfileNotifier
import io.gatekeeper.util.Utility

/**
 * Экран «Заморозка»: автозаморозка по блокировке, задержка, пакетные действия,
 * ярлыки и список автозаморозки целиком -- в настройках, а не по одной
 * снежинке в списке приложений.
 */
@Suppress("TooManyFunctions")
class FreezeSettingsFragment : SettingsSubFragment() {
    private var autoFreezeQuery = ""
    private var autoFreezeApps: List<io.gatekeeper.util.ApplicationInfoWrapper>? = null
    private var autoFreezePackages: Set<String> = emptySet()

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_freeze)
        findPreference<SettingsSearchPreference>(SETTINGS_AUTO_FREEZE_SEARCH)
            ?.onQueryChanged = { query ->
                autoFreezeQuery = query.trim()
                val category = findPreference<PreferenceCategory>(SETTINGS_AUTO_FREEZE_LIST)
                val work = serviceWork
                val apps = autoFreezeApps
                if (category != null && work != null && apps != null) {
                    showAutoFreezeEntries(category, autoFreezePackages, apps, work)
                }
            }
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
            .map { formatMinutesDelay(requireContext(), it) }
            .toTypedArray()
        pref.entryValues = AUTO_FREEZE_DELAY_SECONDS
            .map { it.toString() }
            .toTypedArray()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setAutoFreezeDelay((newState as String).toInt())
            pref.summary = formatMinutesDelay(requireContext(), manager.getAutoFreezeDelay())
            true
        }
        pref.summary = formatMinutesDelay(requireContext(), manager.getAutoFreezeDelay())
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
        findPreference<Preference>(SETTINGS_AUTO_FREEZE_CLEAR_ALL)
            ?.setOnPreferenceClickListener {
                confirmClearAll(this)
                true
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
        autoFreezePackages = packages
        autoFreezeApps = null
        findPreference<Preference>(SETTINGS_AUTO_FREEZE_CLEAR_ALL)?.apply {
            isEnabled = packages.isNotEmpty()
            summary = getString(R.string.settings_auto_freeze_clear_all_summary)
        }
        val work = serviceWork
        if (packages.isEmpty() || work == null) {
            removeAutoFreezeEntries(category)
            val empty = Preference(requireContext()).apply {
                title = getString(R.string.settings_auto_freeze_list_empty)
                isEnabled = false
                isIconSpaceReserved = false
            }
            category.addPreference(empty)
            showBatchCounts(this, packages.size, null)
            return
        }
        Thread {
            val apps = fetchApps(work)
            postOnUi {
                autoFreezeApps = apps.orEmpty()
                showAutoFreezeEntries(category, packages, apps, work)
            }
        }.start()
    }

    private fun showAutoFreezeEntries(
        category: PreferenceCategory,
        packages: Set<String>,
        apps: List<io.gatekeeper.util.ApplicationInfoWrapper>?,
        work: io.gatekeeper.services.IGatekeeperService,
    ) {
        removeAutoFreezeEntries(category)
        val appsByPackage = apps?.associateBy { it.getPackageName() }
        val labels = appsByPackage?.mapValues { it.value.getLabel() }
        val query = autoFreezeQuery
        val visiblePackages = packages.filter { pkg ->
            query.isBlank() ||
                pkg.contains(query, ignoreCase = true) ||
                labels?.get(pkg).orEmpty().contains(query, ignoreCase = true)
        }
        showBatchCounts(this, packages.size, apps)
        if (visiblePackages.isEmpty()) {
            val empty = Preference(requireContext()).apply {
                title = getString(R.string.settings_auto_freeze_search_empty)
                isEnabled = false
                isIconSpaceReserved = false
            }
            category.addPreference(empty)
            return
        }
        visiblePackages.sortedBy { labels?.get(it) ?: it }.forEach { pkg ->
            val pref = Preference(requireContext()).apply {
                key = pkg
                title = labels?.get(pkg) ?: pkg
                summary = pkg
                isIconSpaceReserved = true
                setIcon(R.drawable.ic_apps)
                setOnPreferenceClickListener {
                    confirmRemove(this@FreezeSettingsFragment, pkg, title.toString())
                    true
                }
            }
            category.addPreference(pref)
            val app = appsByPackage?.get(pkg) ?: return@forEach
            try {
                work.loadIcon(app, object : ILoadIconCallback.Stub() {
                    override fun callback(icon: Bitmap) {
                        postOnUi {
                            if (category.findPreference<Preference>(pkg) === pref) {
                                pref.icon = BitmapDrawable(resources, icon)
                            }
                        }
                    }
                })
            } catch (_: RemoteException) {
            }
        }
    }

    private fun removeAutoFreezeEntries(category: PreferenceCategory) {
        for (index in category.preferenceCount - 1 downTo 1) {
            category.removePreference(category.getPreference(index))
        }
    }

    companion object {
        private const val SETTINGS_AUTO_FREEZE_SERVICE = "settings_auto_freeze_service"
        private const val SETTINGS_AUTO_FREEZE_SCOPE = "settings_auto_freeze_scope"
        private const val SETTINGS_AUTO_FREEZE_DELAY = "settings_auto_freeze_delay"
        private const val SETTINGS_SKIP_FOREGROUND = "settings_dont_freeze_foreground"
        const val SETTINGS_FREEZE_ALL = "settings_freeze_all"
        const val SETTINGS_UNFREEZE_ALL = "settings_unfreeze_all"
        private const val SETTINGS_CREATE_FREEZE_ALL_SHORTCUT = "settings_create_freeze_all_shortcut"
        private const val SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT = "settings_create_unfreeze_all_shortcut"
        private const val SETTINGS_AUTO_FREEZE_CLEAR_ALL = "settings_auto_freeze_clear_all"
        private const val SETTINGS_AUTO_FREEZE_LIST = "settings_auto_freeze_list"
        private const val SETTINGS_AUTO_FREEZE_SEARCH = "settings_auto_freeze_search"

        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}

class SettingsSearchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    var onQueryChanged: ((String) -> Unit)? = null
    var queryHintRes: Int = R.string.settings_auto_freeze_search_hint

    init {
        layoutResource = R.layout.preference_auto_freeze_search
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val search = holder.itemView.findViewById<SearchView>(R.id.settings_search_view)
            ?: return
        search.setIconifiedByDefault(false)
        search.isIconified = false
        search.queryHint = context.getString(queryHintRes)
        search.setQuery(queryText, false)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                queryText = newText.orEmpty()
                onQueryChanged?.invoke(queryText)
                return true
            }
        })
    }

    private var queryText = ""
}

/** Счётчики кнопок «Заморозить все» / «Разморозить все» по факту списка. */
private fun showBatchCounts(
    fragment: FreezeSettingsFragment,
    listSize: Int,
    apps: List<io.gatekeeper.util.ApplicationInfoWrapper>?,
) {
    fragment.findPreference<Preference>(FreezeSettingsFragment.SETTINGS_FREEZE_ALL)?.summary =
        if (listSize == 0) {
            fragment.getString(R.string.settings_batch_freeze_empty)
        } else {
            fragment.resources.getQuantityString(
                R.plurals.settings_batch_apps_in_list, listSize, listSize
            )
        }
    fragment.findPreference<Preference>(FreezeSettingsFragment.SETTINGS_UNFREEZE_ALL)?.summary =
        when {
            listSize == 0 -> fragment.getString(R.string.settings_batch_frozen_none)
            apps == null -> fragment.getString(R.string.settings_batch_frozen_none)
            else -> {
                val list = LocalStorageManager.getInstance()
                    .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                val frozen = apps.count { it.isHidden() && it.getPackageName() in list }
                if (frozen == 0) {
                    fragment.getString(R.string.settings_batch_frozen_none)
                } else {
                    fragment.resources.getQuantityString(
                        R.plurals.settings_batch_frozen_now, frozen, frozen
                    )
                }
            }
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
            AutoFreezeDefaults.optOutOfAutoFreeze(pkg)
            AntiSpyManager.syncAutoFreezeListToWorkProfile(fragment.requireContext())
            ProfileNotifier.scheduleAppListRefresh(fragment.requireContext())
            fragment.fillAutoFreezeList()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun confirmClearAll(fragment: FreezeSettingsFragment) {
    val local = LocalStorageManager.getInstance()
    val packages = local
        .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
        .toSet()
    if (packages.isEmpty()) {
        fragment.fillAutoFreezeList()
        return
    }
    AlertDialog.Builder(fragment.requireContext())
        .setTitle(R.string.settings_auto_freeze_clear_all_title)
        .setMessage(
            fragment.getString(
                R.string.settings_auto_freeze_clear_all_message,
                packages.size,
            )
        )
        .setPositiveButton(R.string.ca_remove_action) { _, _ ->
            packages.forEach(AutoFreezeDefaults::optOutOfAutoFreeze)
            local.setStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                emptyArray(),
            )
            local.setStringList(
                LocalStorageManager.PREF_PENDING_NEW_PACKAGES,
                emptyArray(),
            )
            AntiSpyManager.syncAutoFreezeListToWorkProfile(
                fragment.requireContext(),
                force = true,
            )
            ProfileNotifier.scheduleAppListRefresh(fragment.requireContext())
            fragment.fillAutoFreezeList()
            GatekeeperToast.show(
                fragment.requireContext(),
                R.string.settings_auto_freeze_clear_all_done,
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
