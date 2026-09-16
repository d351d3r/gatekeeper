package io.gatekeeper.ui.settings

import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.CalendarRouting
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager

/**
 * Экран «Мосты между копиями»: две дырки в изоляции, которые до сих пор жили
 * только в контекстном меню строки приложения и потому были не видны -- узнать,
 * кому они выданы, можно было лишь долгим нажатием на каждое приложение
 * по очереди. Обе настраиваются по приложению и обе выключены по умолчанию.
 */
@Suppress("TooManyFunctions")
class BridgesSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_bridges)
        bindCheckBox(
            CREATE_PERSONAL_CALENDAR_EVENTS,
            manager.getCreatePersonalCalendarEventsEnabled(),
        ) {
            manager.setCreatePersonalCalendarEventsEnabled(it)
            true
        }
        bindCalendarRouting()
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
            val calendarState = try {
                work.localCalendarRoutingState
            } catch (_: RemoteException) {
                null
            }
            postOnUi {
                if (apps == null || widgets == null || interaction == null) {
                    showUnavailable()
                    return@postOnUi
                }
                interactionPackages = interaction
                applyCalendarState(calendarState)
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
        findPreference<SwitchPreferenceCompat>(LOCAL_CALENDAR_ROUTING)?.isEnabled = false
        findPreference<ListPreference>(LOCAL_CALENDAR_TARGET)?.isEnabled = false
    }

    private fun bindCalendarRouting() {
        val target = findPreference<ListPreference>(LOCAL_CALENDAR_TARGET) ?: return
        val toggle = findPreference<SwitchPreferenceCompat>(LOCAL_CALENDAR_ROUTING) ?: return
        target.isPersistent = false
        toggle.isPersistent = false
        target.setOnPreferenceClickListener { loadCalendarTargets(target) }
        target.setOnPreferenceChangeListener { _, value -> changeCalendarTarget(target, toggle, value) }
        toggle.setOnPreferenceChangeListener { _, value -> changeCalendarRouting(value as Boolean) }
    }

    private fun loadCalendarTargets(target: ListPreference): Boolean {
        val work = serviceWork ?: return true
        val encoded = try {
            work.writableGoogleCalendars
        } catch (_: RemoteException) {
            emptyList()
        }
        val calendars = encoded.mapNotNull(CalendarRouting::decodeTarget)
        if (calendars.isEmpty()) {
            GatekeeperToast.show(requireContext(), R.string.settings_local_calendar_no_targets)
        } else {
            target.entries = calendars.map {
                if (it.displayName.isBlank()) {
                    it.accountName
                } else {
                    "${it.displayName} - ${it.accountName}"
                }
            }.toTypedArray()
            target.entryValues = calendars.map { it.id.toString() }.toTypedArray()
        }
        return calendars.isEmpty()
    }

    @Suppress("ReturnCount")
    private fun changeCalendarTarget(
        target: ListPreference,
        toggle: SwitchPreferenceCompat,
        value: Any,
    ): Boolean {
        val id = value.toString().toLongOrNull() ?: return false
        val work = serviceWork ?: return false
        val error = try {
            work.setLocalCalendarRouting(id, toggle.isChecked)
        } catch (_: RemoteException) {
            "transport"
        }
        if (error != null) {
            GatekeeperToast.show(requireContext(), R.string.settings_local_calendar_failed)
            return false
        }
        calendarTargetId = id
        target.summary = target.entries
            ?.zip(target.entryValues.orEmpty())
            ?.firstOrNull { it.second.toString() == id.toString() }
            ?.first
            ?: getString(R.string.settings_local_calendar_selected, id)
        return true
    }

    @Suppress("ReturnCount")
    private fun changeCalendarRouting(enabled: Boolean): Boolean {
        if (enabled && calendarTargetId < 0L) {
            GatekeeperToast.show(requireContext(), R.string.settings_local_calendar_choose_first)
            return false
        }
        val work = serviceWork ?: return false
        val error = try {
            work.setLocalCalendarRouting(calendarTargetId, enabled)
        } catch (_: RemoteException) {
            "transport"
        }
        if (error != null) {
            GatekeeperToast.show(requireContext(), R.string.settings_local_calendar_failed)
        }
        return error == null
    }

    private fun applyCalendarState(encoded: String?) {
        val parts = encoded?.split('|', limit = 2).orEmpty()
        if (parts.size != 2) return
        val enabled = parts[0] == "1"
        calendarTargetId = parts[1].toLongOrNull() ?: -1L
        findPreference<SwitchPreferenceCompat>(LOCAL_CALENDAR_ROUTING)?.apply {
            isEnabled = true
            isChecked = enabled
        }
        findPreference<ListPreference>(LOCAL_CALENDAR_TARGET)?.apply {
            isEnabled = true
            value = calendarTargetId.takeIf { it >= 0L }?.toString()
            summary = if (calendarTargetId >= 0L) {
                getString(R.string.settings_local_calendar_selected, calendarTargetId)
            } else {
                getString(R.string.settings_local_calendar_target_desc)
            }
        }
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
        private const val CREATE_PERSONAL_CALENDAR_EVENTS =
            "settings_create_personal_calendar_events"
        private const val LOCAL_CALENDAR_TARGET = "settings_local_calendar_target"
        private const val LOCAL_CALENDAR_ROUTING = "settings_local_calendar_routing"
    }

    private var interactionPackages: Set<String> = emptySet()
    private var calendarTargetId: Long = -1L
}
