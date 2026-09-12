package io.gatekeeper.ui.settings

import android.os.Bundle
import android.os.RemoteException
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.VpnRoutingAdvice
import io.gatekeeper.util.VpnTunnelDetector

/** Экран «Сторож VPN»: включение, триггеры, область, задержка, банер маршрутизации. */
class AntiSpySettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_antispy)
        bindCheckBox(
            SETTINGS_ANTI_SPY_ENABLED,
            manager.getAntiSpyWatchConfig().enabled,
            manager::setAntiSpyWatchEnabled,
        )
        bindCheckBox(
            SETTINGS_ANTI_SPY_FREEZE_ON_VPN,
            manager.getAntiSpyWatchConfig().freezeOnVpn,
            manager::setAntiSpyFreezeOnVpn,
        )
        bindCheckBox(
            SETTINGS_ANTI_SPY_FREEZE_ON_SCREEN_LOCK,
            manager.getAntiSpyWatchConfig().freezeOnScreenLock,
            manager::setAntiSpyFreezeOnScreenLock,
        )
        bindCheckBox(
            SETTINGS_ANTI_SPY_NOTIFY_ONLY,
            manager.getAntiSpyWatchConfig().notifyOnly,
            manager::setAntiSpyNotifyOnly,
        )
        bindScope()
        bindDelay()
    }

    override fun onResume() {
        super.onResume()
        updateRoutingBanner()
        updateScopeSummary()
        updateDelaySummary()
    }

    private fun bindCheckBox(key: String, checked: Boolean, apply: (Boolean) -> Unit) {
        val pref = findPreference<SwitchPreferenceCompat>(key) ?: return
        pref.isChecked = checked
        pref.setOnPreferenceChangeListener { _, newState ->
            apply(newState as Boolean)
            true
        }
    }

    private fun bindScope() {
        val pref = findPreference<DropDownPreference>(SETTINGS_ANTI_SPY_SCOPE) ?: return
        pref.entries = AntiSpyFreezeScope.entries
            .map { getString(scopeTitle(it)) }
            .toTypedArray()
        pref.entryValues = AntiSpyFreezeScope.entries
            .map { it.stored.toString() }
            .toTypedArray()
        pref.value = manager.getAntiSpyWatchConfig().scope.stored.toString()
        pref.setOnPreferenceChangeListener { _, newState ->
            val scope = AntiSpyFreezeScope.fromStored((newState as String).toInt())
            manager.setAntiSpyFreezeScope(scope)
            updateScopeSummary()
            true
        }
    }

    private fun updateScopeSummary() {
        findPreference<DropDownPreference>(SETTINGS_ANTI_SPY_SCOPE)?.summary =
            getString(scopeTitle(manager.getAntiSpyWatchConfig().scope))
    }

    private fun scopeTitle(scope: AntiSpyFreezeScope): Int = when (scope) {
        AntiSpyFreezeScope.AUTO_FREEZE_LIST -> R.string.settings_anti_spy_scope_list
        AntiSpyFreezeScope.WHOLE_WORK_PROFILE -> R.string.settings_anti_spy_scope_all
    }

    private fun bindDelay() {
        val pref = findPreference<DropDownPreference>(SETTINGS_ANTI_SPY_DELAY) ?: return
        pref.entries = AntiSpyWatchConfig.DELAY_CHOICES_SECONDS
            .map { delayTitle(it) }
            .toTypedArray()
        pref.entryValues = AntiSpyWatchConfig.DELAY_CHOICES_SECONDS
            .map { it.toString() }
            .toTypedArray()
        pref.value = manager.getAntiSpyWatchConfig().delaySeconds.toString()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setAntiSpyFreezeDelay((newState as String).toInt())
            updateDelaySummary()
            true
        }
    }

    private fun updateDelaySummary() {
        findPreference<DropDownPreference>(SETTINGS_ANTI_SPY_DELAY)?.summary =
            delayTitle(manager.getAntiSpyWatchConfig().delaySeconds)
    }

    private fun delayTitle(seconds: Int): String = if (seconds == 0) {
        getString(R.string.format_immediately)
    } else {
        getString(R.string.format_seconds, seconds)
    }

    /**
     * Подсказка по факту, а не по теории: заморозка по подъему VPN имеет смысл только тогда,
     * когда трафик рабочего профиля действительно идет через туннель. Профиль -- отдельный
     * пользователь со своей маршрутизацией, и ответ на этот вопрос знает только он сам.
     */
    private fun updateRoutingBanner() {
        val workTunneled = try {
            serviceWork?.isDefaultNetworkTunneled()
        } catch (_: RemoteException) {
            null
        }
        val advice = VpnRoutingAdvice.evaluate(
            VpnTunnelDetector.isDefaultNetworkTunneled(requireContext()),
            workTunneled,
        )
        findPreference<Preference>(SETTINGS_ANTI_SPY_ROUTING)?.setSummary(
            when (advice) {
                VpnRoutingAdvice.WORK_PROFILE_BYPASSES_TUNNEL ->
                    R.string.settings_anti_spy_routing_bypass
                VpnRoutingAdvice.WORK_PROFILE_IN_TUNNEL ->
                    R.string.settings_anti_spy_routing_in_tunnel
                VpnRoutingAdvice.VPN_DOWN -> R.string.settings_anti_spy_routing_vpn_down
                VpnRoutingAdvice.WORK_PROFILE_UNREACHABLE ->
                    R.string.settings_anti_spy_routing_unknown
            }
        )
    }

    companion object {
        private const val SETTINGS_ANTI_SPY_ENABLED = "settings_anti_spy_enabled"
        private const val SETTINGS_ANTI_SPY_FREEZE_ON_VPN = "settings_anti_spy_freeze_on_vpn"
        private const val SETTINGS_ANTI_SPY_FREEZE_ON_SCREEN_LOCK =
            "settings_anti_spy_freeze_on_screen_lock"
        private const val SETTINGS_ANTI_SPY_NOTIFY_ONLY = "settings_anti_spy_notify_only"
        private const val SETTINGS_ANTI_SPY_SCOPE = "settings_anti_spy_scope"
        private const val SETTINGS_ANTI_SPY_DELAY = "settings_anti_spy_delay"
        private const val SETTINGS_ANTI_SPY_ROUTING = "settings_anti_spy_routing"
    }
}
