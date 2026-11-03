package io.gatekeeper.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.GatekeeperToast
import androidx.core.content.ContextCompat
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.ProfileNotifier
import io.gatekeeper.util.RelayAuthorization
import io.gatekeeper.util.SameProcessTokens
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkProfilePolicy
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver

class DummyActivity : Activity() {
    private var isProfileOwner = false
    private var policyManager: DevicePolicyManager? = null
    private lateinit var antiSpyFlow: AntiSpyLaunchFlow
    private val installFlow by lazy { PackageInstallFlow(this, isProfileOwner) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        antiSpyFlow = AntiSpyLaunchFlow(this)
        policyManager = getSystemService(DevicePolicyManager::class.java)
        isProfileOwner = policyManager!!.isProfileOwnerApp(packageName)
        init()
    }

    /**
     * Единственный гейт activity: свой процесс, своя подпись или публичное действие.
     * Решение -- [RelayAuthorization] (покрыт JVM-тестами); здесь только данные интента.
     *
     * Статус установщика принимается только через неэкспортированный псевдоним
     * [INSTALLER_CALLBACK_ALIAS]: сама DummyActivity экспортирована, и раньше любое
     * приложение могло прислать ей «установлено» без подписи (S4).
     */
    private fun isAuthorized(intent: Intent): Boolean {
        val request = RelayAuthorization.Request(
            action = intent.action,
            componentClassName = intent.component?.className,
            isProfileOwner = isProfileOwner,
            isSettingUp = isSettingUp(),
        )
        val authorized = RelayAuthorization.isAuthorized(
            request,
            sameProcessToken = { SameProcessTokens.consume(intent.getStringExtra(EXTRA_SAME_PROCESS_NONCE)) },
            signature = { AuthenticationUtility.checkIntent(intent) },
        )
        if (!authorized) Log.w(TAG, "relay action rejected: ${intent.action}")
        return authorized
    }

    private fun isSettingUp(): Boolean =
        LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_IS_SETTING_UP)

    private fun init() {
        val intent = intent

        if (!isAuthorized(intent)) {
            finish()
            return
        }

        // Политика профиля -- только после проверки: раньше любой интент, в том числе
        // чужой и неавторизованный, сначала запускал полную пересборку политик (S4).
        // Пересборка идет, только если набор изменился (F4).
        if (isProfileOwner) {
            WorkProfilePolicy.enforceWorkProfilePolicies(this)
            SettingsManager.getInstance().applyAll()
            if (requestNotificationPermissionIfNeeded(intent.action)) return
        }
        dispatch(intent)
    }

    private fun dispatch(intent: Intent) {
        val handler = actionHandlers[intent.action]
        if (handler != null) {
            handler()
        } else {
            finish()
        }
    }

    /**
     * E-4: запрос POST_NOTIFICATIONS не должен блокировать установку/удаление -- раньше
     * диалог разрешения вставал перед install-потоком и первый тап по «Установить» уходил
     * мимо. Для install-действий разрешение не спрашиваем (hasRequestedPermission не
     * выставляется -- спросим при обычном входе). Обычно до диалога не доходит: политика
     * профиля выдает разрешение рабочей копии сама.
     *
     * @return true, если показан системный запрос; действие продолжится в
     * [onRequestPermissionsResult] без повторной проверки (подпись к тому времени может
     * устареть, а одноразовый токен -- быть израсходован).
     */
    @SuppressLint("InlinedApi") // POST_NOTIFICATIONS запрашивается только при shouldAsk (API 33+)
    private fun requestNotificationPermissionIfNeeded(action: String?): Boolean {
        synchronized(DummyActivity::class.java) {
            val isInstallAction = action == INSTALL_PACKAGE || action == UNINSTALL_PACKAGE
            val shouldAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasRequestedPermission && FINALIZE_PROVISION != action && !isInstallAction
            if (!shouldAsk) return false
            hasRequestedPermission = true
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_PERMISSION_POST_NOTIFICATIONS
                )
            }
            return !granted
        }
    }

    /**
     * Диспетчер действий вместо when на ~20 веток: ccn init() остаётся единицей,
     * подпись/авторизация гейтятся выше, до таблицы. Ключи -- константы ProfileActions.
     */
    private val workRelayHandlers by lazy { WorkRelayHandlers(this) }
    private val dummyBatchFlow by lazy { DummyBatchFlow(this) }
    private val unfreezeLaunchFlow by lazy { UnfreezeLaunchFlow(this) }
    private val provisionFlow by lazy { ProvisionFlow(this) }
    private val fileShuttleFlow by lazy { FileShuttleFlow(this) }
    private val serviceRelayFlow by lazy { ServiceRelayFlow(this) }
    private val powerSettingsFlow by lazy { PowerSettingsFlow(this) }
    private val profileRepairFlow by lazy { ProfileRepairFlow(this) }

    /** Внутренние аксессоры для выделенных флоу (WorkRelayHandlers, DummyBatchFlow и др.). */
    internal val isProfileOwnerInternal: Boolean
        get() = isProfileOwner
    internal val antiSpyFlowInternal: AntiSpyLaunchFlow
        get() = antiSpyFlow
    internal val policyManagerInternal: android.app.admin.DevicePolicyManager
        get() = policyManager!!
    internal fun adminComponent(): ComponentName =
        ComponentName(this, GatekeeperDeviceAdminReceiver::class.java)

    private val actionHandlers: Map<String, () -> Unit> by lazy {
        mapOf(
            START_SERVICE to serviceRelayFlow::handleStartService,
            TRY_START_SERVICE to ::actionTryStartService,
            INSTALL_PACKAGE to installFlow::installPackage,
            UNINSTALL_PACKAGE to installFlow::uninstallPackage,
            FINALIZE_PROVISION to provisionFlow::handleFinalizeProvision,
            UNFREEZE_AND_LAUNCH to unfreezeLaunchFlow::handleUnfreezeAndLaunch,
            PUBLIC_UNFREEZE_AND_LAUNCH to unfreezeLaunchFlow::handleUnfreezeAndLaunch,
            PUBLIC_FREEZE_ALL to dummyBatchFlow::handlePublicFreezeAll,
            PUBLIC_UNFREEZE_ALL to dummyBatchFlow::handlePublicUnfreezeAll,
            SHOW_TOAST to ::actionShowToast,
            REFRESH_MAIN_APP_LIST to ::actionRefreshMainAppList,
            FREEZE_ALL_IN_LIST to workRelayHandlers::handleFreezeAllInList,
            UNFREEZE_ALL_IN_LIST to workRelayHandlers::handleUnfreezeAllInList,
            REMOVE_UNFREEZE_SHORTCUT to workRelayHandlers::handleRemoveUnfreezeShortcut,
            REMOVE_UNFREEZE_SHORTCUT_2 to workRelayHandlers::handleRemoveUnfreezeShortcut,
            START_FILE_SHUTTLE to fileShuttleFlow::handleStartFileShuttle,
            START_FILE_SHUTTLE_2 to fileShuttleFlow::handleStartFileShuttle,
            SYNCHRONIZE_PREFERENCE to workRelayHandlers::handleSynchronizePreference,
            SYNC_ISOLATION to workRelayHandlers::handleSyncIsolation,
            SYNC_ANTI_SPY_VPN_WATCH to workRelayHandlers::handleSyncAntiSpyVpnWatch,
            SYNC_FREEZE_SERVICE to workRelayHandlers::handleSyncFreezeService,
            VPN_SESSION_COMPLETE to dummyBatchFlow::handleVpnSessionComplete,
            OPEN_POWER_SETTINGS to powerSettingsFlow::handleOpenPowerSettings,
            OPEN_MAIN_APP to ::actionOpenMainApp,
            REQUEST_REPAIR to profileRepairFlow::handleRequestRepair,
            PACKAGEINSTALLER_CALLBACK to { installFlow.handleCallback(intent) },
            WIPE_PROFILE to workRelayHandlers::handleWipeProfile,
        )
    }

    /**
     * Нажатие на уведомление, отправленное из рабочего профиля: MainActivity там
     * выключена (WorkProfilePolicy), поэтому открываем ее на личной стороне.
     */
    private fun actionOpenMainApp() {
        if (!isProfileOwner) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
        finish()
    }

    private fun actionTryStartService() {
        setResult(RESULT_OK)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        init()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ANTI_SPY_VPN) {
            if (resultCode == RESULT_OK) {
                restartAntiSpyGate()
            } else {
                antiSpyFlow.showPermissionDeniedDialog { restartAntiSpyGate() }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        installFlow.onLegacyActivityResult(resultCode, requestCode)
    }

    private fun restartAntiSpyGate() {
        antiSpyFlow.runGate(
            requireNotNull(intent.getStringExtra("packageName")),
            {
                antiSpyFlow.forwardUnfreezeAndLaunchToWorkProfile()
                finish()
            },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            // Прерванный запрос приходит с пустыми массивами: это отказ, а не падение.
            FileShuttleFlow.REQUEST_EXTERNAL_STORAGE ->
                fileShuttleFlow.onStoragePermissionResult(
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                )
            REQUEST_PERMISSION_POST_NOTIFICATIONS -> dispatch(intent)
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    internal fun finishBatchShortcutFlow() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION") // замена overrideActivityTransition есть только с API 34
            overridePendingTransition(0, 0)
        }
    }

    internal fun forwardBatchToMainActivityIfVisible(batchAction: String): Boolean {
        val mainAction = when (batchAction) {
            PUBLIC_FREEZE_ALL -> MainActivity.ACTION_BATCH_FREEZE_ALL
            PUBLIC_UNFREEZE_ALL -> MainActivity.ACTION_BATCH_UNFREEZE_ALL
            else -> null
        }
        val forwarded = mainAction != null && MainActivity.isResumed
        if (forwarded) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = mainAction
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            finishBatchShortcutFlow()
        }
        return forwarded
    }

    private fun actionShowToast() {
        val resId = intent.getIntExtra(MainActivity.EXTRA_TOAST_RES_ID, 0)
        if (resId != 0) {
            GatekeeperToast.show(this, resId)
        }
        if (!isProfileOwner) {
            ProfileNotifier.deliverAppListRefreshInMainProcess(this)
            ProfileNotifier.scheduleAppListRefresh(this, longArrayOf(700L, 2000L, 4500L))
        }
        finishBatchShortcutFlow()
    }

    private fun actionRefreshMainAppList() {
        if (!isProfileOwner) {
            ProfileNotifier.deliverAppListRefreshInMainProcess(this)
            ProfileNotifier.scheduleAppListRefresh(this, longArrayOf(700L, 2000L, 4500L))
        }
        finishBatchShortcutFlow()
    }

    companion object {
        const val FINALIZE_PROVISION = ProfileActions.FINALIZE_PROVISION
        const val START_SERVICE = ProfileActions.START_SERVICE
        const val TRY_START_SERVICE = ProfileActions.TRY_START_SERVICE
        const val INSTALL_PACKAGE = ProfileActions.INSTALL_PACKAGE
        const val UNINSTALL_PACKAGE = ProfileActions.UNINSTALL_PACKAGE
        const val UNFREEZE_AND_LAUNCH = ProfileActions.UNFREEZE_AND_LAUNCH
        const val PUBLIC_UNFREEZE_AND_LAUNCH = ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH
        const val PUBLIC_FREEZE_ALL = ProfileActions.PUBLIC_FREEZE_ALL
        const val PUBLIC_UNFREEZE_ALL = ProfileActions.PUBLIC_UNFREEZE_ALL
        const val SHOW_TOAST = ProfileActions.SHOW_TOAST
        const val REFRESH_MAIN_APP_LIST = ProfileActions.REFRESH_MAIN_APP_LIST
        const val FREEZE_ALL_IN_LIST = ProfileActions.FREEZE_ALL_IN_LIST
        const val UNFREEZE_ALL_IN_LIST = ProfileActions.UNFREEZE_ALL_IN_LIST
        const val REMOVE_UNFREEZE_SHORTCUT =
            ProfileActions.REMOVE_UNFREEZE_SHORTCUT
        const val REMOVE_UNFREEZE_SHORTCUT_2 =
            ProfileActions.REMOVE_UNFREEZE_SHORTCUT_2
        const val START_FILE_SHUTTLE = ProfileActions.START_FILE_SHUTTLE
        const val START_FILE_SHUTTLE_2 = ProfileActions.START_FILE_SHUTTLE_2
        const val SYNCHRONIZE_PREFERENCE = ProfileActions.SYNCHRONIZE_PREFERENCE
        const val SYNC_ISOLATION = ProfileActions.SYNC_ISOLATION
        const val SYNC_ANTI_SPY_VPN_WATCH =
            ProfileActions.SYNC_ANTI_SPY_VPN_WATCH
        const val SYNC_FREEZE_SERVICE = ProfileActions.SYNC_FREEZE_SERVICE
        const val VPN_SESSION_COMPLETE =
            ProfileActions.VPN_SESSION_COMPLETE
        const val PACKAGEINSTALLER_CALLBACK = ProfileActions.PACKAGEINSTALLER_CALLBACK
        const val OPEN_POWER_SETTINGS = ProfileActions.OPEN_POWER_SETTINGS
        const val OPEN_MAIN_APP = ProfileActions.OPEN_MAIN_APP
        const val REQUEST_REPAIR = ProfileActions.REQUEST_REPAIR
        const val WIPE_PROFILE = ProfileActions.WIPE_PROFILE

        /** Неэкспортированный псевдоним DummyActivity для статуса PackageInstaller (манифест). */
        const val INSTALLER_CALLBACK_ALIAS = RelayAuthorization.INSTALLER_CALLBACK_ALIAS

        private const val TAG = "DummyActivity"

        /** Extra FREEZE_ALL_IN_LIST: запуск от фоновой VPN-заморозки (иначе — ручной). */
        const val EXTRA_VPN_ORIGIN = "vpn_origin"

        /** Одноразовый токен действий своего процесса (RelayAuthorization.SAME_PROCESS_ACTIONS). */
        private const val EXTRA_SAME_PROCESS_NONCE = "same_process_nonce"

        private const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3
        internal const val REQUEST_ANTI_SPY_VPN = 4

        private var hasRequestedPermission = false

        fun registerSameProcessRequest(intent: Intent) {
            intent.putExtra(EXTRA_SAME_PROCESS_NONCE, SameProcessTokens.issue())
        }
    }
}
