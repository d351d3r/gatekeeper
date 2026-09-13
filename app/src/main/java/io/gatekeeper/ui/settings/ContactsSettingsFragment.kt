package io.gatekeeper.ui.settings

import android.os.Bundle
import io.gatekeeper.R

/**
 * Экран «Контакты и звонки»: обе ручки закрывают рабочий профиль от личного, а не
 * наоборот, поэтому они собраны в одну группу с названным направлением. Вторая
 * группа отвечает на вопрос, который эти ручки порождают: как тогда дать клону
 * нужные номера. Переключателя «синхронизировать контакты» здесь нет и не будет --
 * он противоречит docs/threat_model.md.
 */
class ContactsSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_contacts)
        bindCheckBox(SETTINGS_BLOCK_CONTACTS_SEARCHING, manager.getBlockContactsSearchingEnabled()) {
            manager.setBlockContactsSearchingEnabled(it)
            true
        }
        bindCheckBox(SETTINGS_BLOCK_CALLER_ID, manager.getBlockCallerIdEnabled()) {
            manager.setBlockCallerIdEnabled(it)
            true
        }
    }

    companion object {
        const val SETTINGS_BLOCK_CONTACTS_SEARCHING = "settings_block_contacts_searching"
        const val SETTINGS_BLOCK_CALLER_ID = "settings_block_caller_id"
    }
}
