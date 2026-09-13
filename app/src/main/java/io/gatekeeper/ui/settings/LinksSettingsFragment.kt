package io.gatekeeper.ui.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.gatekeeper.R
import io.gatekeeper.util.CrossProfileLinkRules
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager

/**
 * Экран «Ссылки в профиле»: домены, чьи http(s)-ссылки уходят в браузер рабочего
 * профиля. Осознанная дырка в изоляции (C2): список персистит сервис рабочего
 * профиля, он же владелец соответствующих intent-фильтров. Правила показаны
 * строками, а не одним текстовым полем: домен добавляется и снимается по одному,
 * и видно, сколько их и какие.
 */
class LinksSettingsFragment : SettingsSubFragment() {

    private val rules: MutableList<String> = mutableListOf()

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_links)
        rules.addAll(
            LocalStorageManager.getInstance()
                .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
        )
        findPreference<Preference>(SETTINGS_LINKS_ADD)?.setOnPreferenceClickListener {
            askForDomain()
            true
        }
        fillRules()
    }

    private fun fillRules() {
        val category = findPreference<PreferenceCategory>(SETTINGS_LINKS_LIST) ?: return
        category.removeAll()
        if (rules.isEmpty()) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.settings_cross_profile_link_rules_none)
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
            return
        }
        for (rule in rules) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = rule
                    summary = getString(R.string.settings_links_remove_hint)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        confirmRemoval(rule)
                        true
                    }
                }
            )
        }
    }

    private fun askForDomain() {
        // setView кладет поле вплотную к краям диалога: отступ задаем сами.
        val padding = (DIALOG_PADDING_DP * resources.displayMetrics.density).toInt()
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.settings_links_add_desc)
        }
        val holder = FrameLayout(requireContext()).apply {
            setPaddingRelative(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_links_add)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ -> addDomain(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Разбор нескольких доменов сразу уже был написан (CrossProfileLinkRules.parseRules),
     * но диалог звал normalize на одну строку: ввести список было нельзя, и подсказка
     * об этом не говорила.
     */
    private fun addDomain(raw: String) {
        val parsed = CrossProfileLinkRules.parseRules(raw)
        if (parsed.isEmpty()) {
            GatekeeperToast.show(requireContext(), R.string.settings_links_invalid)
            return
        }
        val added = parsed.filterNot { it in rules }
        if (added.isEmpty()) return
        applyRules(rules + added)
    }

    private fun confirmRemoval(rule: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.settings_links_remove_confirm, rule))
            .setPositiveButton(R.string.settings_links_remove) { _, _ ->
                applyRules(rules - rule)
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Правило живет там, где стоят intent-фильтры: сначала применяем в рабочем
     * профиле, и только после успеха сохраняем локально.
     */
    private fun applyRules(next: List<String>) {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.settings_cross_profile_link_rules_failed)
            return
        }
        Thread {
            val applied = runCatching { work.setCrossProfileLinkRules(next) }.getOrDefault(false)
            postOnUi {
                if (!applied) {
                    GatekeeperToast.show(
                        requireContext(),
                        R.string.settings_cross_profile_link_rules_failed,
                    )
                    return@postOnUi
                }
                LocalStorageManager.getInstance().setStringList(
                    LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES,
                    next.toTypedArray(),
                )
                rules.clear()
                rules.addAll(next)
                fillRules()
            }
        }.start()
    }

    companion object {
        private const val DIALOG_PADDING_DP = 24
        private const val SETTINGS_LINKS_LIST = "settings_links_list"
        private const val SETTINGS_LINKS_ADD = "settings_links_add"
    }
}
