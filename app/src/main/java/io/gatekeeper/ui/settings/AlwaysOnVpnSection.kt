package io.gatekeeper.ui.settings

import android.os.RemoteException
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.GatekeeperToast

/**
 * Always-on VPN профиля (C6). Владелец профиля вправе закрепить туннель внутри
 * профиля и запретить трафик мимо него. Это сильнее сторожа: сторож замечает
 * чужой туннель, который профиль не защищает, а закрепленный always-on дает
 * туннель, который защищает по-настоящему, и при обрыве оставляет профиль без
 * сети вместо утечки в открытый интернет.
 *
 * Вынесено из фрагмента: экран сторожа и без того держит пять настроек.
 */
internal class AlwaysOnVpnSection(private val fragment: AntiSpySettingsFragment) {

    private data class State(val packageName: String, val lockdown: Boolean)

    fun bind() {
        fragment.findPreference<Preference>(KEY_APP)?.setOnPreferenceClickListener {
            showPicker()
            true
        }
        fragment.findPreference<SwitchPreferenceCompat>(KEY_LOCKDOWN)
            ?.setOnPreferenceChangeListener { _, newState ->
                val current = readState() ?: return@setOnPreferenceChangeListener false
                apply(current.packageName, newState as Boolean)
                false
            }
    }

    /** Состояние живет в рабочем профиле: перечитываем на каждый показ экрана. */
    fun refresh() {
        val appPref = fragment.findPreference<Preference>(KEY_APP) ?: return
        val lockPref = fragment.findPreference<SwitchPreferenceCompat>(KEY_LOCKDOWN)
        val work = fragment.serviceWork
        if (work == null) {
            appPref.summary = fragment.getString(R.string.settings_alwayson_unavailable)
            appPref.isEnabled = false
            lockPref?.isEnabled = false
            return
        }
        Thread {
            val state = readState()
            val label = state?.let { labelOf(work, it.packageName) }
            fragment.postOnUi {
                appPref.isEnabled = true
                appPref.summary = label
                    ?: fragment.getString(R.string.settings_alwayson_none)
                lockPref?.isChecked = state?.lockdown == true
                lockPref?.isEnabled = state != null
            }
        }.start()
    }

    private fun readState(): State? {
        val raw = fragment.serviceWork?.let {
            try {
                it.alwaysOnVpnState
            } catch (_: RemoteException) {
                null
            }
        }
        return raw?.takeIf { it.isNotEmpty() }?.split("|")?.let {
            State(it[0], it.getOrNull(1) == "true")
        }
    }

    private fun labelOf(work: IGatekeeperService, packageName: String): String? =
        vpnApps(work).firstOrNull { it.first == packageName }?.second ?: packageName

    private fun vpnApps(work: IGatekeeperService): List<Pair<String, String>> = try {
        work.vpnCapableApps.orEmpty().mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    } catch (_: RemoteException) {
        emptyList()
    }

    private fun showPicker() {
        val work = fragment.serviceWork ?: return
        Thread {
            val apps = vpnApps(work)
            fragment.postOnUi {
                if (apps.isEmpty()) {
                    GatekeeperToast.show(
                        fragment.requireContext(),
                        R.string.settings_alwayson_no_apps,
                    )
                    return@postOnUi
                }
                val titles = (listOf(fragment.getString(R.string.settings_alwayson_none)) +
                    apps.map { it.second }).toTypedArray()
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(R.string.settings_alwayson_app)
                    .setItems(titles) { _, which ->
                        val pkg = if (which == 0) "" else apps[which - 1].first
                        apply(pkg, readState()?.lockdown == true && pkg.isNotEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /**
     * Пустой пакет снимает закрепление. Отказ бывает штатным: не всякое VPN-
     * приложение умеет always-on, и не всякая прошивка дает его закрепить.
     */
    private fun apply(packageName: String, lockdown: Boolean) {
        val work = fragment.serviceWork ?: return
        Thread {
            val error = try {
                work.setAlwaysOnVpn(packageName, lockdown)
            } catch (_: RemoteException) {
                ""
            }
            fragment.postOnUi {
                if (error != null) {
                    GatekeeperToast.show(
                        fragment.requireContext(),
                        fragment.getString(R.string.settings_alwayson_failed, error),
                    )
                }
                refresh()
            }
        }.start()
    }

    private companion object {
        const val KEY_APP = "settings_alwayson_app"
        const val KEY_LOCKDOWN = "settings_alwayson_lockdown"
    }
}
