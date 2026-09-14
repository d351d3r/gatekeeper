package io.gatekeeper.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.Preference
import io.gatekeeper.BuildConfig
import io.gatekeeper.R

/** Экран «Прочее»: заглушка оплаты, версия и три ссылки наружу. */
class MiscSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_misc)
        bindPaymentStub()
        bindVersion()
        bindLinks()
    }

    /**
     * До Android 13 заглушка разблокировала системный выбор платежки для профиля --
     * оставляем свитч. С Android 13 платежка по умолчанию -- роль «Кошелек» на все
     * устройство, приложением рабочего профиля она быть не может (запрет платформы),
     * а старый механизм убран: свитч бесполезен, вместо него честная инфо-строка.
     */
    private fun bindPaymentStub() {
        val stub = findPreference<SwitchPreferenceCompat>(SETTINGS_PAYMENT_STUB)
        val wall = findPreference<Preference>(SETTINGS_PAYMENT_WALL)
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        stub?.isVisible = legacy
        wall?.isVisible = !legacy
        if (legacy) {
            stub?.isChecked = manager.getPaymentStubEnabled()
            stub?.setOnPreferenceChangeListener { _, newState ->
                manager.setPaymentStubEnabled(newState as Boolean)
                true
            }
        }
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
        private const val SETTINGS_PAYMENT_STUB = "settings_payment_stub"
        private const val SETTINGS_PAYMENT_WALL = "settings_payment_wall"
        private const val SETTINGS_VERSION = "settings_version"

        /** Сводка строки -- человеческий текст, адрес живет отдельно. */
        private val LINKS = listOf(
            "settings_user_guide" to R.string.settings_user_guide_url,
            "settings_known_problems" to R.string.settings_known_problems_url,
            "settings_source_code" to R.string.settings_source_code_url,
        )
    }
}
