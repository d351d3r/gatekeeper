package io.gatekeeper.ui

import android.os.Bundle
import android.os.RemoteException
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.ui.settings.SettingsSubFragment
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

/**
 * Корень настроек (редизайн, .ai/ui-redesign/settings-plan.md): семь строк, каждая
 * ведёт на подэкран и несёт состояние, а не пересказ функции. Вся проводка --
 * null-safe: подэкраны живут на [SettingsSubFragment] и сами решают, что им нужно.
 */
class SettingsFragment : PreferenceFragmentCompat() {
    private val manager = SettingsManager.getInstance()
    private var serviceWork: IGatekeeperService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceWork = IGatekeeperService.Stub.asInterface(
            arguments?.getBinder(SettingsSubFragment.ARG_PROFILE_SERVICE)
        ) ?: IGatekeeperService.Stub.asInterface(
            IntentCompat.getParcelableExtra(
                requireActivity().intent, "extras", Bundle::class.java
            )?.getBinder("profile_service")
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(
            view.findViewById(androidx.preference.R.id.recycler_view)
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPaddingRelative(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_settings)
        openDiagnosticsIfRequested()
    }

    /** Глубокая ссылка из MainActivity: сразу открываем экран диагностики. */
    private fun openDiagnosticsIfRequested() {
        val requested = requireActivity().intent
            .getBooleanExtra(SettingsActivity.EXTRA_OPEN_POWER_DIAGNOSTICS, false)
        val pref = findPreference<Preference>(ROOT_DIAGNOSTICS)
        if (!requested || pref == null) return
        view?.post {
            (activity as? OnPreferenceStartFragmentCallback)?.onPreferenceStartFragment(this, pref)
        }
    }

    override fun onResume() {
        super.onResume()
        // Аппаратная BACK обходит onSupportNavigateUp: при возврате на корень
        // onResume корня срабатывает повторно -- тут и сбрасываем заголовок.
        // Важно: через supportActionBar, а не activity.setTitle -- после явного
        // присвоения title (экран подэкрана) ToolbarActionBar игнорирует
        // setWindowTitle, и тулбар «залипает».
        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.settings)
        updateFreezeSummary()
        updateAntiSpySummary()
        updateFilesSummary()
        updateLinksSummary()
        updateCertsSummary()
    }

    private fun updateFreezeSummary() {
        val summary = if (manager.getAutoFreezeServiceEnabled()) {
            getString(R.string.settings_root_freeze_on, manager.getAutoFreezeDelay() / SECONDS_IN_MINUTE)
        } else {
            getString(R.string.settings_root_freeze_off)
        }
        findPreference<Preference>(ROOT_FREEZE)?.summary = summary
    }

    private fun updateAntiSpySummary() {
        findPreference<Preference>(ROOT_ANTISPY)?.summary = getString(
            if (manager.getAntiSpyWatchConfig().enabled) {
                R.string.settings_root_antispy_on
            } else {
                R.string.settings_root_antispy_off
            }
        )
    }

    private fun updateFilesSummary() {
        findPreference<Preference>(ROOT_FILES)?.summary = getString(
            if (manager.getCrossProfileFileChooserEnabled()) {
                R.string.settings_root_files_on
            } else {
                R.string.settings_root_files_off
            }
        )
    }

    private fun updateLinksSummary() {
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
        findPreference<Preference>(ROOT_LINKS)?.summary = if (rules.isEmpty()) {
            getString(R.string.settings_cross_profile_link_rules_none)
        } else {
            getString(R.string.settings_cross_profile_link_rules_current, rules.joinToString(", "))
        }
    }

    /** Сертификаты считает рабочий профиль -- спрашиваем его в фоне, без блокировки UI. */
    private fun updateCertsSummary() {
        val work = serviceWork ?: return
        Thread {
            val count = try {
                work.getInstalledCaCertificates()?.size
            } catch (_: RemoteException) {
                null
            } ?: return@Thread
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                findPreference<Preference>(ROOT_CERTS)?.summary = if (count == 0) {
                    getString(R.string.settings_root_certs_none)
                } else {
                    resources.getQuantityString(R.plurals.settings_root_certs_count, count, count)
                }
            }
        }.start()
    }

    companion object {
        private const val ROOT_FREEZE = "settings_root_freeze"
        private const val ROOT_ANTISPY = "settings_root_antispy"
        private const val ROOT_FILES = "settings_root_files"
        private const val ROOT_LINKS = "settings_root_links"
        private const val ROOT_CERTS = "settings_root_certs"
        private const val ROOT_DIAGNOSTICS = "settings_root_diagnostics"
        private const val SECONDS_IN_MINUTE = 60
    }
}
