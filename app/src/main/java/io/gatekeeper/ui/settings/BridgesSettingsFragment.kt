package io.gatekeeper.ui.settings

import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager

/**
 * Экран «Мосты между копиями»: две дырки в изоляции, которые до сих пор жили
 * только в контекстном меню строки приложения и потому были не видны -- узнать,
 * кому они выданы, можно было лишь долгим нажатием на каждое приложение
 * по очереди. Обе настраиваются по приложению и обе выключены по умолчанию.
 */
class BridgesSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_bridges)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // setCrossProfilePackages появился в Android 11: до него раздела нет.
            findPreference<SwitchPreferenceCompat>(MASTER_INTERACTION)?.isVisible = false
            findPreference<PreferenceCategory>(CATEGORY_INTERACTION)?.isVisible = false
        } else {
            findPreference<SwitchPreferenceCompat>(MASTER_INTERACTION)
                ?.setOnPreferenceChangeListener { _, newState ->
                    setAllInteraction(newState as Boolean)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        val work = serviceWork
        if (work == null) {
            showUnavailable()
            return
        }
        findPreference<SwitchPreferenceCompat>(MASTER_INTERACTION)?.apply {
            isEnabled = true
            isChecked = manager.getCrossProfileInteractionAllEnabled()
        }
        Thread {
            // Connected apps includes platform and preinstalled apps such as Calendar.
            val apps = fetchApps(work)?.filter { it.isInstalled() }
            val widgets = readList { work.crossProfileWidgetProviders }
            if (manager.getCrossProfileInteractionAllEnabled()) {
                try {
                    work.applyAllCrossProfilePackages()
                } catch (_: RemoteException) {
                }
            }
            val interaction = readList { work.crossProfilePackages }
            postOnUi {
                if (apps == null || widgets == null || interaction == null) {
                    showUnavailable()
                    return@postOnUi
                }
                interactionPackages = interaction
                fillApps(
                    CATEGORY_WIDGETS,
                    apps,
                    widgets,
                    apply = { pkg, on -> setWidget(pkg, on) },
                )
                fillApps(
                    CATEGORY_INTERACTION,
                    apps,
                    interaction,
                    apply = { pkg, on -> setInteraction(pkg, on, interaction) },
                    editable = !manager.getCrossProfileInteractionAllEnabled(),
                )
            }
        }.start()
    }

    private fun readList(read: () -> List<String>?): Set<String>? = try {
        read()?.toSet() ?: emptySet()
    } catch (_: RemoteException) {
        null
    }

    private fun showUnavailable() {
        for (key in listOf(CATEGORY_WIDGETS, CATEGORY_INTERACTION)) {
            val note = findPreference<Preference>("${key}_note") ?: continue
            note.summary = getString(R.string.settings_bridges_unavailable)
        }
        findPreference<SwitchPreferenceCompat>(MASTER_INTERACTION)?.isEnabled = false
    }

    /**
     * Строки приложений добавляются после пояснения и пересобираются на каждый
     * onResume: состояние политики живет в рабочем профиле, а не у нас.
     */
    private fun fillApps(
        categoryKey: String,
        apps: List<ApplicationInfoWrapper>,
        allowed: Set<String>,
        apply: (String, Boolean) -> Boolean,
        editable: Boolean = true,
    ) {
        val category = findPreference<PreferenceCategory>(categoryKey) ?: return
        val note = findPreference<Preference>("${categoryKey}_note")
        category.removeAll()
        note?.let { category.addPreference(it) }
        if (apps.isEmpty()) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.settings_bridges_no_apps)
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
            return
        }
        for (app in apps) {
            val pkg = app.getPackageName()
            category.addPreference(
                SwitchPreferenceCompat(requireContext()).apply {
                    key = "$categoryKey.$pkg"
                    title = app.getLabel() ?: pkg
                    summary = pkg
                    isChecked = pkg in allowed
                    isEnabled = editable
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, newState ->
                        val enabled = newState as Boolean
                        val done = apply(pkg, enabled)
                        if (!done) {
                            GatekeeperToast.show(
                                requireContext(),
                                R.string.settings_bridges_failed,
                            )
                        }
                        done
                    }
                }
            )
        }
    }

    private fun setWidget(pkg: String, enabled: Boolean): Boolean = try {
        serviceWork?.setCrossProfileWidgetProviderEnabled(pkg, enabled) == true
    } catch (_: RemoteException) {
        false
    }

    /**
     * setCrossProfilePackages принимает весь список целиком, поэтому правим копию
     * прочитанного набора и отправляем её обратно.
     */
    private fun setInteraction(pkg: String, enabled: Boolean, current: Set<String>): Boolean {
        val work = serviceWork ?: return false
        val next = current.toMutableSet()
        if (enabled) next.add(pkg) else next.remove(pkg)
        return try {
            work.crossProfilePackages = next.toList()
            interactionPackages = next
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun setAllInteraction(enabled: Boolean): Boolean {
        val work = serviceWork ?: return false
        val storage = LocalStorageManager.getInstance()
        return try {
            if (enabled) enableAllInteraction(work, storage) else disableAllInteraction(work, storage)
        } catch (_: RemoteException) {
            false
        }
    }

    private fun enableAllInteraction(
        work: io.gatekeeper.services.IGatekeeperService,
        storage: LocalStorageManager,
    ): Boolean {
        val current = readList { work.crossProfilePackages }
        return if (current == null) {
            false
        } else {
            interactionPackages = current
            storage.setStringList(
                LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_MANUAL,
                interactionPackages.toTypedArray(),
            )
            if (!work.applyAllCrossProfilePackages()) {
                storage.remove(LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_MANUAL)
                false
            } else {
                manager.setCrossProfileInteractionAllEnabled(true)
                interactionPackages = readList { work.crossProfilePackages } ?: interactionPackages
                true
            }
        }
    }

    private fun disableAllInteraction(
        work: io.gatekeeper.services.IGatekeeperService,
        storage: LocalStorageManager,
    ): Boolean {
        manager.setCrossProfileInteractionAllEnabled(false)
        val restored = storage.getStringList(
            LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_MANUAL
        ).toSet()
        work.crossProfilePackages = restored.toList()
        storage.remove(LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_MANUAL)
        interactionPackages = restored
        return true
    }

    companion object {
        private const val CATEGORY_WIDGETS = "settings_bridges_widgets"
        private const val CATEGORY_INTERACTION = "settings_bridges_interaction"
        private const val MASTER_INTERACTION = "settings_bridges_interaction_all"
    }

    private var interactionPackages: Set<String> = emptySet()
}
