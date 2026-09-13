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
import io.gatekeeper.ui.settings.formatMinutesDelay
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
        preferenceScreen?.let(io.gatekeeper.ui.settings.PreferenceTitles::allowMultiline)
        openDiagnosticsIfRequested()
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
    }

    /**
     * Глубокая ссылка из приложения: сразу открываем названный экран. Зовется из
     * onViewCreated, а не из onCreatePreferences: там у фрагмента еще нет view,
     * и view?.post молча ничего не делал.
     */
    private fun openDiagnosticsIfRequested() {
        val key = requireActivity().intent.getStringExtra(SettingsActivity.EXTRA_OPEN_SCREEN)
        val pref = key?.let { findPreference<Preference>(it) }
        if (pref == null) return
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
        updateSummaries()
        updateRemoteSummaries()
    }

    /** Строки, под которыми стоит текущее состояние, а не описание экрана. */
    private fun updateStateSummaries() {
        val access = io.gatekeeper.util.AccessState.count(requireContext())
        setSummary(
            ROOT_ACCESSES,
            getString(R.string.settings_root_accesses_desc, access.granted, access.total),
        )
        setSummary(
            ROOT_ISOLATION,
            getString(
                when (io.gatekeeper.util.IsolationPolicies.matchedPreset(manager.getIsolationState())) {
                    false -> R.string.isolation_preset_convenient
                    true -> R.string.isolation_preset_isolated
                    else -> R.string.isolation_preset_custom
                }
            ),
        )
    }

    /** Сводки, которые считаются на месте: состояние строки, а не пересказ функции. */
    private fun updateSummaries() {
        updateStateSummaries()
        setSummary(
            ROOT_FREEZE,
            if (manager.getAutoFreezeServiceEnabled()) {
                getString(
                    R.string.settings_root_freeze_on,
                    formatMinutesDelay(requireContext(), manager.getAutoFreezeDelay()),
                )
            } else {
                getString(R.string.settings_root_freeze_off)
            },
        )
        setSummary(
            ROOT_FILES,
            getString(
                if (manager.getCrossProfileFileChooserEnabled()) {
                    R.string.settings_root_files_on
                } else {
                    R.string.settings_root_files_off
                }
            ),
        )
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
        setSummary(
            ROOT_LINKS,
            if (rules.isEmpty()) {
                getString(R.string.settings_cross_profile_link_rules_none)
            } else {
                getString(
                    R.string.settings_cross_profile_link_rules_current,
                    rules.joinToString(", "),
                )
            },
        )
        setSummary(ROOT_CONTACTS, getString(contactsSummaryRes()))
    }

    /** Обе ручки закрывают рабочий профиль от личного, поэтому считаем их вместе. */
    private fun contactsSummaryRes(): Int {
        val closed = listOf(
            manager.getBlockContactsSearchingEnabled(),
            manager.getBlockCallerIdEnabled(),
        ).count { it }
        return when (closed) {
            0 -> R.string.settings_root_contacts_open
            1 -> R.string.settings_root_contacts_partial
            else -> R.string.settings_root_contacts_closed
        }
    }

    private fun setSummary(key: String, text: String) {
        findPreference<Preference>(key)?.summary = text
    }

    /**
     * Сертификаты и мосты между копиями считает рабочий профиль -- спрашиваем его
     * одним заходом в фоне, без блокировки UI.
     */
    private fun updateRemoteSummaries() {
        val work = serviceWork ?: return
        Thread {
            val certs = try {
                work.getInstalledCaCertificates()?.size
            } catch (_: RemoteException) {
                null
            }
            val bridged = try {
                (work.getCrossProfileWidgetProviders().orEmpty() +
                    work.getCrossProfilePackages().orEmpty()).distinct().size
            } catch (_: RemoteException) {
                null
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (certs != null) {
                    setSummary(
                        ROOT_CERTS,
                        if (certs == 0) {
                            getString(R.string.settings_root_certs_none)
                        } else {
                            resources.getQuantityString(
                                R.plurals.settings_root_certs_count,
                                certs,
                                certs,
                            )
                        },
                    )
                }
                if (bridged != null) {
                    setSummary(
                        ROOT_BRIDGES,
                        if (bridged == 0) {
                            getString(R.string.settings_root_bridges_none)
                        } else {
                            resources.getQuantityString(
                                R.plurals.settings_root_bridges_count,
                                bridged,
                                bridged,
                            )
                        },
                    )
                }
            }
        }.start()
    }

    companion object {
        private const val ROOT_ISOLATION = "settings_root_isolation"
        private const val ROOT_ACCESSES = "settings_root_accesses"

        private const val ROOT_FREEZE = "settings_root_freeze"
        private const val ROOT_FILES = "settings_root_files"
        private const val ROOT_LINKS = "settings_root_links"
        private const val ROOT_CERTS = "settings_root_certs"
        private const val ROOT_BRIDGES = "settings_root_bridges"
        private const val ROOT_CONTACTS = "settings_root_contacts"
    }
}
