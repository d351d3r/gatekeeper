package io.gatekeeper.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.color.DynamicColors
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import io.gatekeeper.services.IShelterService
import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.VpnRoutingAdvice
import io.gatekeeper.util.VpnTunnelDetector

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
    private val manager = SettingsManager.getInstance()
    private var serviceWork: IShelterService? = null

    private var prefCrossProfileFileChooser: CheckBoxPreference? = null
    private var prefBlockContactsSearching: CheckBoxPreference? = null
    private var prefAutoFreezeService: CheckBoxPreference? = null
    private var prefSkipForeground: CheckBoxPreference? = null
    private var prefPaymentStub: CheckBoxPreference? = null
    private var prefDynamicColors: CheckBoxPreference? = null
    private var prefAutoFreezeDelay: DropDownPreference? = null
    private var prefAntiSpyEnabled: CheckBoxPreference? = null
    private var prefAntiSpyFreezeOnVpn: CheckBoxPreference? = null
    private var prefAntiSpyFreezeOnScreenLock: CheckBoxPreference? = null
    private var prefAntiSpyNotifyOnly: CheckBoxPreference? = null
    private var prefAntiSpyScope: DropDownPreference? = null
    private var prefAntiSpyDelay: DropDownPreference? = null

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

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_settings)
        serviceWork = IShelterService.Stub.asInterface(
            (requireActivity().intent.getParcelableExtra<Bundle>("extras"))!!.getBinder("profile_service")
        )

        findPreference<Preference>(SETTINGS_VERSION)!!.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findPreference<Preference>(SETTINGS_SOURCE_CODE)!!
            .setOnPreferenceClickListener(this::openSummaryUrl)

        setUpDynamicColors()

        prefCrossProfileFileChooser = findPreference(SETTINGS_CROSS_PROFILE_FILE_CHOOSER)
        prefCrossProfileFileChooser!!.isChecked = manager.getCrossProfileFileChooserEnabled()
        prefCrossProfileFileChooser!!.onPreferenceChangeListener = this

        prefBlockContactsSearching = findPreference(SETTINGS_BLOCK_CONTACTS_SEARCHING)
        prefBlockContactsSearching!!.isChecked = manager.getBlockContactsSearchingEnabled()
        prefBlockContactsSearching!!.onPreferenceChangeListener = this

        prefPaymentStub = findPreference(SETTINGS_PAYMENT_STUB)
        prefPaymentStub!!.isChecked = manager.getPaymentStubEnabled()
        prefPaymentStub!!.onPreferenceChangeListener = this

        prefAutoFreezeService = findPreference(SETTINGS_AUTO_FREEZE_SERVICE)
        prefAutoFreezeService!!.isChecked = manager.getAutoFreezeServiceEnabled()
        prefAutoFreezeService!!.onPreferenceChangeListener = this

        prefAutoFreezeDelay = findPreference(SETTINGS_AUTO_FREEZE_DELAY)
        prefAutoFreezeDelay!!.onPreferenceChangeListener = this
        prefAutoFreezeDelay!!.entries = AUTO_FREEZE_DELAY_SECONDS
            .map { getString(R.string.format_minutes, it / 60) }
            .toTypedArray()
        prefAutoFreezeDelay!!.entryValues = AUTO_FREEZE_DELAY_SECONDS
            .map { it.toString() }
            .toTypedArray()
        updateAutoFreezeDelay()

        prefSkipForeground = findPreference(SETTINGS_SKIP_FOREGROUND)
        prefSkipForeground!!.isChecked = manager.getSkipForegroundEnabled()
        prefSkipForeground!!.onPreferenceChangeListener = this

        setUpAntiSpyWatch()

        findPreference<Preference>(SETTINGS_UNFREEZE_ALL)!!
            .setOnPreferenceClickListener(this::startBatchUnfreeze)
        findPreference<Preference>(SETTINGS_FREEZE_ALL)!!
            .setOnPreferenceClickListener(this::startBatchFreeze)
        findPreference<Preference>(SETTINGS_CREATE_FREEZE_ALL_SHORTCUT)!!
            .setOnPreferenceClickListener(this::createFreezeAllShortcut)
        findPreference<Preference>(SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT)!!
            .setOnPreferenceClickListener(this::createUnfreezeAllShortcut)

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            prefCrossProfileFileChooser!!.isEnabled = false
        }

        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) {
            prefCrossProfileFileChooser!!.isEnabled = false
        }
    }

    /**
     * Динамический цвет существует с Android 12. Ниже показывать переключатель нечего --
     * категория убирается целиком, чтобы не оставлять неработающий пункт.
     */
    private fun setUpDynamicColors() {
        if (!DynamicColors.isDynamicColorAvailable()) {
            findPreference<PreferenceCategory>(SETTINGS_APPEARANCE)?.isVisible = false
            return
        }
        prefDynamicColors = findPreference(SETTINGS_DYNAMIC_COLORS)
        prefDynamicColors!!.isChecked = manager.getDynamicColorsEnabled()
        prefDynamicColors!!.onPreferenceChangeListener = this
    }

    private fun updateAutoFreezeDelay() {
        prefAutoFreezeDelay!!.summary =
            getString(R.string.format_minutes, manager.getAutoFreezeDelay() / 60)
    }

    private fun setUpAntiSpyWatch() {
        val config = manager.getAntiSpyWatchConfig()

        prefAntiSpyEnabled = findPreference(SETTINGS_ANTI_SPY_ENABLED)
        prefAntiSpyEnabled!!.isChecked = config.enabled
        prefAntiSpyEnabled!!.onPreferenceChangeListener = this

        prefAntiSpyFreezeOnVpn = findPreference(SETTINGS_ANTI_SPY_FREEZE_ON_VPN)
        prefAntiSpyFreezeOnVpn!!.isChecked = config.freezeOnVpn
        prefAntiSpyFreezeOnVpn!!.onPreferenceChangeListener = this

        prefAntiSpyFreezeOnScreenLock = findPreference(SETTINGS_ANTI_SPY_FREEZE_ON_SCREEN_LOCK)
        prefAntiSpyFreezeOnScreenLock!!.isChecked = config.freezeOnScreenLock
        prefAntiSpyFreezeOnScreenLock!!.onPreferenceChangeListener = this

        prefAntiSpyNotifyOnly = findPreference(SETTINGS_ANTI_SPY_NOTIFY_ONLY)
        prefAntiSpyNotifyOnly!!.isChecked = config.notifyOnly
        prefAntiSpyNotifyOnly!!.onPreferenceChangeListener = this

        prefAntiSpyScope = findPreference(SETTINGS_ANTI_SPY_SCOPE)
        prefAntiSpyScope!!.entries = AntiSpyFreezeScope.entries
            .map { getString(scopeTitle(it)) }
            .toTypedArray()
        prefAntiSpyScope!!.entryValues = AntiSpyFreezeScope.entries
            .map { it.stored.toString() }
            .toTypedArray()
        prefAntiSpyScope!!.value = config.scope.stored.toString()
        prefAntiSpyScope!!.onPreferenceChangeListener = this

        prefAntiSpyDelay = findPreference(SETTINGS_ANTI_SPY_DELAY)
        prefAntiSpyDelay!!.entries = AntiSpyWatchConfig.DELAY_CHOICES_SECONDS
            .map { delayTitle(it) }
            .toTypedArray()
        prefAntiSpyDelay!!.entryValues = AntiSpyWatchConfig.DELAY_CHOICES_SECONDS
            .map { it.toString() }
            .toTypedArray()
        prefAntiSpyDelay!!.value = config.delaySeconds.toString()
        prefAntiSpyDelay!!.onPreferenceChangeListener = this

        updateAntiSpySummaries(config)
    }

    private fun scopeTitle(scope: AntiSpyFreezeScope): Int = when (scope) {
        AntiSpyFreezeScope.AUTO_FREEZE_LIST -> R.string.settings_anti_spy_scope_list
        AntiSpyFreezeScope.WHOLE_WORK_PROFILE -> R.string.settings_anti_spy_scope_all
    }

    private fun delayTitle(seconds: Int): String = if (seconds == 0) {
        getString(R.string.format_immediately)
    } else {
        getString(R.string.format_seconds, seconds)
    }

    private fun updateAntiSpySummaries(config: AntiSpyWatchConfig) {
        prefAntiSpyScope!!.summary = getString(scopeTitle(config.scope))
        prefAntiSpyDelay!!.summary = delayTitle(config.delaySeconds)
    }

    /**
     * Подсказка по факту, а не по теории: заморозка по подъему VPN имеет смысл только тогда,
     * когда трафик рабочего профиля действительно идет через туннель. Профиль -- отдельный
     * пользователь со своей маршрутизацией, и ответ на этот вопрос знает только он сам.
     */
    private fun updateAntiSpyRouting() {
        val workTunneled = try {
            serviceWork?.isDefaultNetworkTunneled()
        } catch (_: RemoteException) {
            null
        }
        val advice = VpnRoutingAdvice.evaluate(
            VpnTunnelDetector.isDefaultNetworkTunneled(requireContext()),
            workTunneled,
        )
        findPreference<Preference>(SETTINGS_ANTI_SPY_ROUTING)!!.setSummary(
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

    private fun startBatchFreeze(pref: Preference): Boolean {
        val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL).apply {
            component = ComponentName(requireContext(), DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(intent)
        startActivity(intent)
        return true
    }

    private fun startBatchUnfreeze(pref: Preference): Boolean {
        val intent = Intent(DummyActivity.PUBLIC_UNFREEZE_ALL).apply {
            component = ComponentName(requireContext(), DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(intent)
        startActivity(intent)
        return true
    }

    private fun createFreezeAllShortcut(pref: Preference): Boolean {
        val launchIntent = Intent(requireContext(), DummyActivity::class.java).apply {
            action = DummyActivity.PUBLIC_FREEZE_ALL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        Utility.createLauncherShortcut(
            requireContext(), launchIntent,
            Utility.createBatchShortcutIcon(requireContext(), R.drawable.ic_shortcut_freeze),
            "shelter-freeze-all", getString(R.string.freeze_all_shortcut)
        )
        return true
    }

    private fun createUnfreezeAllShortcut(pref: Preference): Boolean {
        val launchIntent = Intent(requireContext(), DummyActivity::class.java).apply {
            action = DummyActivity.PUBLIC_UNFREEZE_ALL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        Utility.createLauncherShortcut(
            requireContext(), launchIntent,
            Utility.createBatchShortcutIcon(requireContext(), R.drawable.ic_shortcut_unfreeze),
            "shelter-unfreeze-all", getString(R.string.unfreeze_all_shortcut)
        )
        return true
    }

    private fun openSummaryUrl(pref: Preference): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(pref.summary.toString())
        }
        startActivity(intent)
        return true
    }

    override fun onResume() {
        super.onResume()
        updateAutoFreezeDelay()
        updateAntiSpyRouting()
    }

    override fun onPreferenceChange(preference: Preference, newState: Any): Boolean {
        return when (preference) {
            prefCrossProfileFileChooser -> {
                val enabled = newState as Boolean
                if (!enabled) {
                    manager.setCrossProfileFileChooserEnabled(false)
                    return true
                }

                // "Поверх других приложений" здесь больше не требуется: провайдер SAF не
                // делает фоновых стартов activity, для которых это разрешение было обходом BAL.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val hasPermission = ensureSpecialAccessPermission({
                        try {
                            serviceWork!!.hasAllFileAccessPermission() &&
                                Utility.checkAllFileAccessPermission()
                        } catch (_: RemoteException) {
                            false
                        }
                    }, R.string.request_storage_manager, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

                    if (!hasPermission) return false
                }

                manager.setCrossProfileFileChooserEnabled(true)
                true
            }
            prefBlockContactsSearching -> {
                manager.setBlockContactsSearchingEnabled(newState as Boolean)
                true
            }
            prefAutoFreezeService -> {
                manager.setAutoFreezeServiceEnabled(newState as Boolean)
                true
            }
            prefAutoFreezeDelay -> {
                manager.setAutoFreezeDelay((newState as String).toInt())
                updateAutoFreezeDelay()
                true
            }
            prefSkipForeground -> {
                val enabled = newState as Boolean
                if (!enabled) {
                    manager.setSkipForegroundEnabled(false)
                    return true
                }

                val hasPermission = ensureSpecialAccessPermission({
                    try {
                        serviceWork!!.hasUsageStatsPermission() &&
                            Utility.checkUsageStatsPermission(requireContext())
                    } catch (_: RemoteException) {
                        false
                    }
                }, R.string.request_usage_stats, Settings.ACTION_USAGE_ACCESS_SETTINGS)

                if (!hasPermission) return false

                manager.setSkipForegroundEnabled(true)
                true
            }
            prefPaymentStub -> {
                manager.setPaymentStubEnabled(newState as Boolean)
                true
            }
            prefDynamicColors -> {
                manager.setDynamicColorsEnabled(newState as Boolean)
                requireActivity().recreate()
                true
            }
            prefAntiSpyEnabled -> {
                manager.setAntiSpyWatchEnabled(newState as Boolean)
                true
            }
            prefAntiSpyFreezeOnVpn -> {
                manager.setAntiSpyFreezeOnVpn(newState as Boolean)
                true
            }
            prefAntiSpyFreezeOnScreenLock -> {
                manager.setAntiSpyFreezeOnScreenLock(newState as Boolean)
                true
            }
            prefAntiSpyNotifyOnly -> {
                manager.setAntiSpyNotifyOnly(newState as Boolean)
                true
            }
            prefAntiSpyScope -> {
                val scope = AntiSpyFreezeScope.fromStored((newState as String).toInt())
                manager.setAntiSpyFreezeScope(scope)
                prefAntiSpyScope!!.summary = getString(scopeTitle(scope))
                true
            }
            prefAntiSpyDelay -> {
                val seconds = (newState as String).toInt()
                manager.setAntiSpyFreezeDelay(seconds)
                prefAntiSpyDelay!!.summary = delayTitle(seconds)
                true
            }
            else -> false
        }
    }

    private fun interface CheckPermissionCallback {
        fun check(): Boolean
    }

    private fun ensureSpecialAccessPermission(
        checkPermission: CheckPermissionCallback,
        alertRes: Int,
        settingsAction: String
    ): Boolean {
        if (!checkPermission.check()) {
            AlertDialog.Builder(requireContext())
                .setMessage(alertRes)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(settingsAction))
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .show()
            return false
        }
        return true
    }

    companion object {
        private const val SETTINGS_VERSION = "settings_version"
        private const val SETTINGS_SOURCE_CODE = "settings_source_code"
        private const val SETTINGS_CROSS_PROFILE_FILE_CHOOSER = "settings_cross_profile_file_chooser"
        private const val SETTINGS_BLOCK_CONTACTS_SEARCHING = "settings_block_contacts_searching"
        private const val SETTINGS_AUTO_FREEZE_SERVICE = "settings_auto_freeze_service"
        private const val SETTINGS_AUTO_FREEZE_DELAY = "settings_auto_freeze_delay"
        private const val SETTINGS_SKIP_FOREGROUND = "settings_dont_freeze_foreground"
        private const val SETTINGS_PAYMENT_STUB = "settings_payment_stub"
        private const val SETTINGS_APPEARANCE = "settings_appearance"
        private const val SETTINGS_DYNAMIC_COLORS = "settings_dynamic_colors"
        private const val SETTINGS_ANTI_SPY_ENABLED = "settings_anti_spy_enabled"
        private const val SETTINGS_ANTI_SPY_FREEZE_ON_VPN = "settings_anti_spy_freeze_on_vpn"
        private const val SETTINGS_ANTI_SPY_FREEZE_ON_SCREEN_LOCK =
            "settings_anti_spy_freeze_on_screen_lock"
        private const val SETTINGS_ANTI_SPY_NOTIFY_ONLY = "settings_anti_spy_notify_only"
        private const val SETTINGS_ANTI_SPY_SCOPE = "settings_anti_spy_scope"
        private const val SETTINGS_ANTI_SPY_DELAY = "settings_anti_spy_delay"
        private const val SETTINGS_ANTI_SPY_ROUTING = "settings_anti_spy_routing"
        private const val SETTINGS_FREEZE_ALL = "settings_freeze_all"
        private const val SETTINGS_UNFREEZE_ALL = "settings_unfreeze_all"
        private const val SETTINGS_CREATE_FREEZE_ALL_SHORTCUT = "settings_create_freeze_all_shortcut"
        private const val SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT = "settings_create_unfreeze_all_shortcut"

        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}
