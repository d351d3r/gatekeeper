package io.gatekeeper.ui.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import io.gatekeeper.R
import io.gatekeeper.util.CrossProfileLinkRules
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager

/**
 * Экран «Ссылки в профиле»: домены, чьи http(s)-ссылки уходят в браузер рабочего
 * профиля. Осознанная дырка в изоляции (C2): список персистит сервис рабочего
 * профиля, который и владелец соответствующих intent-фильтров.
 */
class LinksSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_links)
        val pref = findPreference<EditTextPreference>(SETTINGS_CROSS_PROFILE_LINK_RULES)
            ?: return
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
            .toList()
        pref.text = rules.joinToString(", ")
        updateSummary(rules)
        pref.setOnPreferenceChangeListener { preference, newValue ->
            onRulesChanged(preference as EditTextPreference, newValue as String)
        }
    }

    private fun updateSummary(rules: List<String>) {
        findPreference<EditTextPreference>(SETTINGS_CROSS_PROFILE_LINK_RULES)?.summary =
            if (rules.isEmpty()) {
                getString(R.string.settings_cross_profile_link_rules_none)
            } else {
                getString(
                    R.string.settings_cross_profile_link_rules_current,
                    rules.joinToString(", "),
                )
            }
    }

    private fun onRulesChanged(pref: EditTextPreference, raw: String): Boolean {
        val rules = CrossProfileLinkRules.parseRules(raw)
        val skipped = raw.split(',', ';', ' ', '\n', '\t')
            .count { it.isNotBlank() && CrossProfileLinkRules.normalize(it) == null }
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.settings_cross_profile_link_rules_failed)
            return false
        }
        Thread {
            val applied = runCatching { work.setCrossProfileLinkRules(rules) }
                .getOrDefault(false)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!applied) {
                    GatekeeperToast.show(
                        requireContext(),
                        R.string.settings_cross_profile_link_rules_failed,
                    )
                    return@runOnUiThread
                }
                LocalStorageManager.getInstance().setStringList(
                    LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES,
                    rules.toTypedArray(),
                )
                pref.text = rules.joinToString(", ")
                updateSummary(rules)
                if (skipped > 0) {
                    GatekeeperToast.show(
                        requireContext(),
                        getString(R.string.settings_cross_profile_link_rules_skipped, skipped),
                    )
                }
            }
        }.start()
        // Персистим сами после успешного применения, а не framework'ом.
        return false
    }

    companion object {
        private const val SETTINGS_CROSS_PROFILE_LINK_RULES = "settings_cross_profile_link_rules"
    }
}
