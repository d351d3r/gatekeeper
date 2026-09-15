package io.gatekeeper.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.Preference
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ThemeMode

/** Экран «Прочее»: заглушка оплаты, версия и три ссылки наружу. */
class MiscSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_misc)
        bindTheme()
        bindPaymentStub()
        bindVersion()
        bindLinks()
    }

    /**
     * Тема: системная / светлая / тёмная. Источник истины -- LocalStorageManager
     * (его читает GatekeeperApplication на старте), поэтому значение ставим и пишем
     * руками, а не через штатную персистенцию преференса.
     */
    private fun bindTheme() {
        val theme = findPreference<ListPreference>(SETTINGS_THEME) ?: return
        theme.value = ThemeMode.current()
        theme.setOnPreferenceChangeListener { _, newValue ->
            val mode = newValue as String
            LocalStorageManager.getInstance().setString(LocalStorageManager.PREF_THEME_MODE, mode)
            ThemeMode.apply(mode)
            true
        }
    }

    /**
     * Заглушка регистрирует пустой платежный HCE-сервис в личном профиле. Android не
     * дает выбрать платежное приложение рабочего профиля, пока в личном нет ни одного
     * платежного сервиса -- заглушка снимает это ограничение (прием Shelter). Дальше
     * приложение профиля назначается в системных настройках NFC/кошелька вручную.
     */
    private fun bindPaymentStub() {
        val stub = findPreference<SwitchPreferenceCompat>(SETTINGS_PAYMENT_STUB) ?: return
        stub.isChecked = manager.getPaymentStubEnabled()
        stub.setOnPreferenceChangeListener { _, newState ->
            val enabled = newState as Boolean
            manager.setPaymentStubEnabled(enabled)
            if (enabled) showPaymentSetupHint()
            true
        }
    }

    /** Шаг настройки нужен ровно в момент включения -- показываем его тогда, а не
     *  держим туториал в подписи свитча. */
    private fun showPaymentSetupHint() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_payment_stub)
            .setMessage(R.string.settings_payment_setup_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun bindVersion() {
        findPreference<Preference>(SETTINGS_VERSION)?.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private fun bindLinks() {
        for ((key, urlRes) in LINKS) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { openUrl(urlRes) }
        }
    }

    private fun openUrl(urlRes: Int): Boolean {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))))
        return true
    }

    companion object {
        private const val SETTINGS_THEME = "settings_theme"
        private const val SETTINGS_PAYMENT_STUB = "settings_payment_stub"
        private const val SETTINGS_VERSION = "settings_version"

        /** Сводка строки -- человеческий текст, адрес живет отдельно. */
        private val LINKS = listOf(
            "settings_user_guide" to R.string.settings_user_guide_url,
            "settings_known_problems" to R.string.settings_known_problems_url,
            "settings_source_code" to R.string.settings_source_code_url,
        )
    }
}
