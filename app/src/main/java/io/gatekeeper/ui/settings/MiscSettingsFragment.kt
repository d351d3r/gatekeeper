package io.gatekeeper.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.Preference
import io.gatekeeper.BuildConfig
import io.gatekeeper.R

/** Экран «Прочее»: контакты и звонки, заглушка оплаты, версия, исходники. */
class MiscSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_misc)
        bindContactsSearching()
        bindCallerId()
        bindPaymentStub()
        bindVersion()
        bindSourceCode()
    }

    private fun bindContactsSearching() {
        val pref = findPreference<SwitchPreferenceCompat>(SETTINGS_BLOCK_CONTACTS_SEARCHING) ?: return
        pref.isChecked = manager.getBlockContactsSearchingEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setBlockContactsSearchingEnabled(newState as Boolean)
            true
        }
    }

    private fun bindCallerId() {
        val pref = findPreference<SwitchPreferenceCompat>(SETTINGS_BLOCK_CALLER_ID) ?: return
        pref.isChecked = manager.getBlockCallerIdEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setBlockCallerIdEnabled(newState as Boolean)
            true
        }
    }

    private fun bindPaymentStub() {
        val pref = findPreference<SwitchPreferenceCompat>(SETTINGS_PAYMENT_STUB) ?: return
        pref.isChecked = manager.getPaymentStubEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            manager.setPaymentStubEnabled(newState as Boolean)
            true
        }
    }

    private fun bindVersion() {
        findPreference<Preference>(SETTINGS_VERSION)?.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun bindSourceCode() {
        findPreference<Preference>(SETTINGS_SOURCE_CODE)
            ?.setOnPreferenceClickListener { openSummaryUrl(it) }
    }

    private fun openSummaryUrl(pref: Preference): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(pref.summary.toString())
        }
        startActivity(intent)
        return true
    }

    companion object {
        private const val SETTINGS_BLOCK_CONTACTS_SEARCHING = "settings_block_contacts_searching"
        private const val SETTINGS_BLOCK_CALLER_ID = "settings_block_caller_id"
        private const val SETTINGS_PAYMENT_STUB = "settings_payment_stub"
        private const val SETTINGS_VERSION = "settings_version"
        private const val SETTINGS_SOURCE_CODE = "settings_source_code"
    }
}
