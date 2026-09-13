package io.gatekeeper.ui.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.util.IsolationPolicies

/**
 * Экран изоляции: два пресета и те же политики по отдельности.
 *
 * Строки собираются из [IsolationPolicies.ALL], а не перечисляются в XML: состав
 * набора задан одной таблицей, и экран не должен расходиться с тем, что реально
 * применяется в профиле.
 */
class IsolationSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_isolation)
        buildPresets()
        buildSwitches()
        refreshPresetState()
    }

    private fun buildPresets() {
        val group = findPreference<PreferenceCategory>(KEY_PRESETS) ?: return
        group.addPreference(
            presetRow(
                KEY_PRESET_CONVENIENT,
                R.string.isolation_preset_convenient,
                R.string.isolation_preset_convenient_desc,
                isolated = false,
            )
        )
        group.addPreference(
            presetRow(
                KEY_PRESET_ISOLATED,
                R.string.isolation_preset_isolated,
                R.string.isolation_preset_isolated_desc,
                isolated = true,
            )
        )
    }

    private fun presetRow(
        prefKey: String,
        titleRes: Int,
        descRes: Int,
        isolated: Boolean,
    ): Preference = Preference(requireContext()).apply {
        key = prefKey
        setTitle(titleRes)
        setSummary(descRes)
        setOnPreferenceClickListener {
            manager.applyIsolationPreset(isolated)
            for (policy in IsolationPolicies.ALL) {
                findPreference<SwitchPreferenceCompat>(policy.key)?.isChecked =
                    manager.getIsolationPolicy(policy.key)
            }
            refreshPresetState()
            true
        }
    }

    private fun buildSwitches() {
        val group = findPreference<PreferenceCategory>(KEY_SWITCHES) ?: return
        val res = requireContext().resources
        val pkg = requireContext().packageName
        for (policy in IsolationPolicies.ALL) {
            val titleId = res.getIdentifier("isolation_${policy.key}", "string", pkg)
            val descId = res.getIdentifier("isolation_${policy.key}_desc", "string", pkg)
            if (titleId == 0) continue
            group.addPreference(
                SwitchPreferenceCompat(requireContext()).apply {
                    key = policy.key
                    setTitle(titleId)
                    if (descId != 0) setSummary(descId)
                    isIconSpaceReserved = false
                    isChecked = manager.getIsolationPolicy(policy.key)
                    isPersistent = false
                    setOnPreferenceChangeListener { _, value ->
                        manager.setIsolationPolicy(policy.key, value as Boolean)
                        refreshPresetState()
                        true
                    }
                }
            )
        }
    }

    /**
     * Выбранный уровень показан галочкой на самой строке, а не отдельной строкой
     * с его названием: та повторяла один из вариантов слово в слово. Собранный
     * руками набор не совпадает ни с одним -- галочки нет ни у кого.
     */
    private fun refreshPresetState() {
        val matched = IsolationPolicies.matchedPreset(manager.getIsolationState())
        mark(KEY_PRESET_CONVENIENT, matched == false)
        mark(KEY_PRESET_ISOLATED, matched == true)
    }

    private fun mark(prefKey: String, selected: Boolean) {
        val row = findPreference<Preference>(prefKey) ?: return
        row.isIconSpaceReserved = true
        if (selected) row.setIcon(R.drawable.ic_check) else row.icon = null
    }

    private companion object {
        const val KEY_PRESETS = "isolation_presets"
        const val KEY_SWITCHES = "isolation_switches"
        const val KEY_PRESET_CONVENIENT = "isolation_preset_convenient"
        const val KEY_PRESET_ISOLATED = "isolation_preset_isolated"
    }
}
