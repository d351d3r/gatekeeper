package io.gatekeeper.ui

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.color.DynamicColors
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.CaCertificates
import io.gatekeeper.util.CrossProfileLinkRules
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.PowerDiagnostics
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.VpnRoutingAdvice
import io.gatekeeper.util.VpnTunnelDetector
import io.gatekeeper.util.GatekeeperToast

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
    private val manager = SettingsManager.getInstance()
    private var serviceWork: IGatekeeperService? = null

    private val selectCertFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onCertFileSelected(uri)
        }

    private var prefCrossProfileFileChooser: CheckBoxPreference? = null
    private var prefMediaMirror: CheckBoxPreference? = null
    private var prefBlockContactsSearching: CheckBoxPreference? = null
    private var prefBlockCallerId: CheckBoxPreference? = null
    private var prefAutoFreezeService: CheckBoxPreference? = null
    private var prefSkipForeground: CheckBoxPreference? = null
    private var prefPaymentStub: CheckBoxPreference? = null
    private var prefLinkRules: EditTextPreference? = null
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
        serviceWork = IGatekeeperService.Stub.asInterface(
            IntentCompat.getParcelableExtra(
                requireActivity().intent, "extras", Bundle::class.java
            )?.getBinder("profile_service")
        )

        findPreference<Preference>(SETTINGS_VERSION)!!.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findPreference<Preference>(SETTINGS_SOURCE_CODE)!!
            .setOnPreferenceClickListener(this::openSummaryUrl)
        findPreference<Preference>(SETTINGS_POWER_DIAGNOSTICS)!!
            .setOnPreferenceClickListener(this::openPowerDiagnostics)

        setUpDynamicColors()

        prefCrossProfileFileChooser = findPreference(SETTINGS_CROSS_PROFILE_FILE_CHOOSER)
        prefCrossProfileFileChooser!!.isChecked = manager.getCrossProfileFileChooserEnabled()
        prefCrossProfileFileChooser!!.onPreferenceChangeListener = this

        prefMediaMirror = findPreference(SETTINGS_MEDIA_MIRROR)
        prefMediaMirror!!.isChecked =
            LocalStorageManager.getInstance().getBoolean(
                LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED
            )
        prefMediaMirror!!.onPreferenceChangeListener = this

        prefBlockContactsSearching = findPreference(SETTINGS_BLOCK_CONTACTS_SEARCHING)
        prefBlockContactsSearching!!.isChecked = manager.getBlockContactsSearchingEnabled()
        prefBlockContactsSearching!!.onPreferenceChangeListener = this

        prefBlockCallerId = findPreference(SETTINGS_BLOCK_CALLER_ID)
        prefBlockCallerId!!.isChecked = manager.getBlockCallerIdEnabled()
        prefBlockCallerId!!.onPreferenceChangeListener = this

        prefPaymentStub = findPreference(SETTINGS_PAYMENT_STUB)
        prefPaymentStub!!.isChecked = manager.getPaymentStubEnabled()
        prefPaymentStub!!.onPreferenceChangeListener = this

        setUpLinkRules()

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

        findPreference<Preference>(SETTINGS_CA_INSTALL)!!
            .setOnPreferenceClickListener {
                launchCertPicker()
                true
            }
        findPreference<Preference>(SETTINGS_CA_INSTALLED)!!
            .setOnPreferenceClickListener {
                showInstalledCaCerts()
                true
            }

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

        if (requireActivity().intent.getBooleanExtra(SettingsActivity.EXTRA_OPEN_POWER_DIAGNOSTICS, false)) {
            view?.post { showPowerDiagnostics() }
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

    /**
     * C2: правила перенаправления ссылок в рабочий профиль. Сервис рабочего
     * профиля сам персистит нормализованный список в префах (он же уезжает в
     * бэкап) и перестраивает фильтры через enforceWorkProfilePolicies; false
     * оттуда = DPM отказал, правило не применено. Ниже персистим ту же копию
     * в префы личного профиля только после подтверждения, чтобы недоехавшее
     * правило не выглядело применённым.
     */
    private fun setUpLinkRules() {
        prefLinkRules = findPreference(SETTINGS_CROSS_PROFILE_LINK_RULES)
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
            .toList()
        prefLinkRules!!.text = rules.joinToString(", ")
        updateLinkRulesSummary(rules)
        prefLinkRules!!.setOnPreferenceChangeListener(this::onLinkRulesChanged)
    }

    private fun updateLinkRulesSummary(rules: List<String>) {
        prefLinkRules!!.summary = if (rules.isEmpty()) {
            getString(R.string.settings_cross_profile_link_rules_none)
        } else {
            getString(R.string.settings_cross_profile_link_rules_current, rules.joinToString(", "))
        }
    }

    private fun onLinkRulesChanged(preference: Preference, newValue: Any): Boolean {
        val pref = preference as EditTextPreference
        val raw = newValue as String
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
                updateLinkRulesSummary(rules)
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
            "gatekeeper-freeze-all", getString(R.string.freeze_all_shortcut)
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
            "gatekeeper-unfreeze-all", getString(R.string.unfreeze_all_shortcut)
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

    private fun openPowerDiagnostics(pref: Preference): Boolean {
        showPowerDiagnostics()
        return true
    }

    private fun showPowerDiagnostics() {
        val appContext = requireContext().applicationContext
        val workService = serviceWork
        Thread {
            val snapshot = collectPowerDiagnostics(appContext, workService)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_power_diagnostics)
                    .setMessage(
                        getString(
                            R.string.settings_power_diagnostics_message,
                            statusTitle(snapshot.mainIgnoringBatteryOptimizations),
                            statusTitle(snapshot.workIgnoringBatteryOptimizations),
                            statusTitle(snapshot.mainBackgroundRestricted),
                            statusTitle(snapshot.workBackgroundRestricted),
                            statusTitle(snapshot.powerSaveMode),
                            statusTitle(snapshot.deviceIdleMode),
                            statusTitle(snapshot.workServiceAlive),
                        )
                    )
                    .setPositiveButton(R.string.power_diagnostics_open_battery) { _, _ ->
                        openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    .setNeutralButton(R.string.power_diagnostics_open_app) { _, _ ->
                        openSettings(appDetailsIntent())
                    }
                    .setNegativeButton(R.string.power_diagnostics_open_work) { _, _ ->
                        openWorkPowerSettings()
                    }
                    .show()
            }
        }.start()
    }

    private fun collectPowerDiagnostics(
        context: Context,
        workService: IGatekeeperService?,
    ): PowerDiagnostics.Snapshot {
        val local = runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val activity = context.getSystemService(ActivityManager::class.java)
            PowerDiagnostics.ProfileSignals(
                ignoringBatteryOptimizations =
                    power?.isIgnoringBatteryOptimizations(context.packageName) ?: return@runCatching null,
                backgroundRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        (activity?.isBackgroundRestricted ?: return@runCatching null),
            )
        }.getOrNull()
        val work = workService?.let { service ->
            runCatching {
                PowerDiagnostics.ProfileSignals(
                    ignoringBatteryOptimizations = service.isIgnoringBatteryOptimizations(),
                    backgroundRestricted = service.isBackgroundRestricted(),
                )
            }.getOrNull()
        }
        val power = context.getSystemService(PowerManager::class.java)
        return PowerDiagnostics.collect(
            mainSignals = local,
            workSignals = work,
            powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull(),
            deviceIdleMode = runCatching { power?.isDeviceIdleMode }.getOrNull(),
            workServiceAlive = workService?.asBinder()?.isBinderAlive,
        )
    }

    private fun statusTitle(status: PowerDiagnostics.Status): String = getString(
        when (status) {
            PowerDiagnostics.Status.YES -> R.string.power_diagnostics_yes
            PowerDiagnostics.Status.NO -> R.string.power_diagnostics_no
            PowerDiagnostics.Status.UNKNOWN -> R.string.power_diagnostics_unknown
        }
    )

    private fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", requireContext().packageName, null)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            GatekeeperToast.show(requireContext(), R.string.power_diagnostics_settings_unavailable)
        }
    }

    private fun openWorkPowerSettings() {
        val intent = Intent(DummyActivity.OPEN_POWER_SETTINGS)
        if (!Utility.tryTransferIntentToProfile(requireContext(), intent)) {
            GatekeeperToast.show(requireContext(), R.string.power_diagnostics_work_unavailable)
            return
        }
        openSettings(intent)
    }

    // ---- Корневые CA рабочего профиля (docs/feature_ca_certs.md) ----
    // Все операции уходят биндером сервису рабочего профиля: только там приложение --
    // владелец профиля, и только туда попадает сертификат. Личный профиль не затрагивается.

    private fun launchCertPicker() {
        try {
            selectCertFile.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
        }
    }

    private fun onCertFileSelected(uri: Uri?) {
        if (uri == null) return
        val bytes = try {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        if (bytes == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
            return
        }
        val cert = CaCertificates.parse(bytes)
        if (cert == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_not_a_certificate)
            return
        }
        // Отпечаток обязан быть показан ДО установки -- пользователь сверяет его
        // с опубликованным издателем (docs/feature_ca_certs.md, требования).
        val info = CaCertificates.infoOf(cert)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ca_install_title)
            .setMessage(
                certDetailsText(info) + "\n\n" +
                    getString(R.string.ca_install_limitation) + "\n\n" +
                    getString(R.string.ca_install_monitored_warning)
            )
            .setPositiveButton(R.string.ca_install_action) { _, _ -> installCaCert(bytes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun certDetailsText(info: CaCertificates.Info): String =
        getString(R.string.ca_cert_details, info.subject, info.sha256)

    private fun installCaCert(cert: ByteArray) {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return
        }
        Thread {
            // null -- успех; "" -- вызов не дошёл по транспорту; иначе текст ошибки DPM.
            val error = try {
                work.installCaCertificate(cert)
            } catch (_: RemoteException) {
                ""
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (error) {
                    null -> GatekeeperToast.show(requireContext(), R.string.ca_install_success)
                    "" -> GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
                    else -> GatekeeperToast.show(
                        requireContext(), getString(R.string.ca_install_failed, error)
                    )
                }
            }
        }.start()
    }

    private fun showInstalledCaCerts() {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return
        }
        Thread {
            val entries = try {
                work.getInstalledCaCertificates()
            } catch (_: RemoteException) {
                null
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (entries == null) {
                    GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
                    return@runOnUiThread
                }
                if (entries.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_ca_installed)
                        .setMessage(R.string.ca_installed_none)
                        .show()
                    return@runOnUiThread
                }
                val infos = entries.mapNotNull(CaCertificates::decodeInfo)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_ca_installed)
                    .setItems(infos.map { certDetailsText(it) }.toTypedArray()) { _, which ->
                        confirmRemoveCaCert(infos[which])
                    }
                    .show()
            }
        }.start()
    }

    private fun confirmRemoveCaCert(info: CaCertificates.Info) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ca_remove_title)
            .setMessage(certDetailsText(info))
            .setPositiveButton(R.string.ca_remove_action) { _, _ -> removeCaCert(info.sha256) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeCaCert(sha256: String) {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return
        }
        Thread {
            val removed = try {
                work.removeCaCertificate(sha256)
            } catch (_: RemoteException) {
                false
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                GatekeeperToast.show(
                    requireContext(),
                    if (removed) R.string.ca_remove_success else R.string.ca_remove_failed
                )
            }
        }.start()
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
                            serviceWork?.hasAllFileAccessPermission() == true &&
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
            prefMediaMirror -> {
                LocalStorageManager.getInstance()
                    .setBoolean(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED, newState as Boolean)
                true
            }
            prefBlockContactsSearching -> {
                manager.setBlockContactsSearchingEnabled(newState as Boolean)
                true
            }
            prefBlockCallerId -> {
                manager.setBlockCallerIdEnabled(newState as Boolean)
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
                        serviceWork?.hasUsageStatsPermission() == true &&
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
        private const val SETTINGS_CROSS_PROFILE_LINK_RULES = "settings_cross_profile_link_rules"
        private const val SETTINGS_MEDIA_MIRROR = "settings_media_mirror"
        private const val SETTINGS_BLOCK_CONTACTS_SEARCHING = "settings_block_contacts_searching"
        private const val SETTINGS_BLOCK_CALLER_ID = "settings_block_caller_id"
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
        private const val SETTINGS_POWER_DIAGNOSTICS = "settings_power_diagnostics"
        private const val SETTINGS_CA_INSTALL = "settings_ca_install"
        private const val SETTINGS_CA_INSTALLED = "settings_ca_installed"
        private const val SETTINGS_FREEZE_ALL = "settings_freeze_all"
        private const val SETTINGS_UNFREEZE_ALL = "settings_unfreeze_all"
        private const val SETTINGS_CREATE_FREEZE_ALL_SHORTCUT = "settings_create_freeze_all_shortcut"
        private const val SETTINGS_CREATE_UNFREEZE_ALL_SHORTCUT = "settings_create_unfreeze_all_shortcut"

        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}
