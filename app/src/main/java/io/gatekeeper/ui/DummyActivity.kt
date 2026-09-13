@file:Suppress("DEPRECATION") // legacy version-gated paths (pre-Q package installer/uninstaller)

package io.gatekeeper.ui

import android.Manifest
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
        if (isProfileOwner) {
            WorkProfilePolicy.enforceWorkProfilePolicies(this)
            WorkProfilePolicy.enforceUserRestrictions(this)
            SettingsManager.getInstance().applyAll()

            synchronized(DummyActivity::class.java) {
                // E-4: запрос POST_NOTIFICATIONS не должен блокировать установку/удаление --
                // раньше диалог разрешения вставал перед install-потоком и первый тап по
                // «Установить» уходил мимо. Для install-действий разрешение не спрашиваем
                // (hasRequestedPermission не выставляется -- спросим при обычном входе).
                val action = intent.action
                val isInstallAction = action == INSTALL_PACKAGE || action == UNINSTALL_PACKAGE
                val shouldAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasRequestedPermission && FINALIZE_PROVISION != action && !isInstallAction
                if (shouldAsk) {
                    hasRequestedPermission = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_PERMISSION_POST_NOTIFICATIONS
                        )
                        return
                    }
                }
            }
        }

        init()
    }

    /** Единственный гейт activity: свой процесс, своя подпись или публичное действие. */
    private fun isAuthorized(intent: Intent): Boolean =
        checkSameProcessRequest(intent) ||
            AuthenticationUtility.checkIntent(intent) ||
            intent.action in ACTIONS_ALLOWED_WITHOUT_SIGNATURE

    private fun init() {
        val intent = intent

        if (!isAuthorized(intent)) {
            finish()
            return
        }

        val handler = actionHandlers[intent.action]
        if (handler != null) {
            handler()
        } else {
            finish()
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
            UNFREEZE_APP to unfreezeLaunchFlow::handleUnfreezeApp,
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
            SYNC_ANTI_SPY_VPN_WATCH to workRelayHandlers::handleSyncAntiSpyVpnWatch,
            SYNC_FREEZE_SERVICE to workRelayHandlers::handleSyncFreezeService,
            VPN_SESSION_COMPLETE to dummyBatchFlow::handleVpnSessionComplete,
            OPEN_POWER_SETTINGS to powerSettingsFlow::handleOpenPowerSettings,
            OPEN_MAIN_APP to ::actionOpenMainApp,
            PACKAGEINSTALLER_CALLBACK to { installFlow.handleCallback(intent) },
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
        if (isProfileOwner) {
            WorkProfilePolicy.enforceWorkProfilePolicies(this)
            WorkProfilePolicy.enforceUserRestrictions(this)
            SettingsManager.getInstance().applyAll()
        }
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
            FileShuttleFlow.REQUEST_EXTERNAL_STORAGE ->
                fileShuttleFlow.onStoragePermissionResult(
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
            REQUEST_PERMISSION_POST_NOTIFICATIONS -> init()
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    internal fun finishBatchShortcutFlow() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
        const val UNFREEZE_APP = ProfileActions.UNFREEZE_APP
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
        const val SYNC_ANTI_SPY_VPN_WATCH =
            ProfileActions.SYNC_ANTI_SPY_VPN_WATCH
        const val SYNC_FREEZE_SERVICE = ProfileActions.SYNC_FREEZE_SERVICE
        const val VPN_SESSION_COMPLETE =
            ProfileActions.VPN_SESSION_COMPLETE
        const val PACKAGEINSTALLER_CALLBACK = ProfileActions.PACKAGEINSTALLER_CALLBACK
        const val OPEN_POWER_SETTINGS = ProfileActions.OPEN_POWER_SETTINGS
        const val OPEN_MAIN_APP = ProfileActions.OPEN_MAIN_APP
        /** Extra FREEZE_ALL_IN_LIST: запуск от фоновой VPN-заморозки (иначе — ручной). */
        const val EXTRA_VPN_ORIGIN = "vpn_origin"

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE = listOf(
            FINALIZE_PROVISION,
            TRY_START_SERVICE,
            PUBLIC_FREEZE_ALL,
            PUBLIC_UNFREEZE_ALL,
            PUBLIC_UNFREEZE_AND_LAUNCH,
            REFRESH_MAIN_APP_LIST,
            SHOW_TOAST,
            // Подпись живет 30 секунд, PendingIntent уведомления -- пока висит
            // уведомление. Действие умеет ровно одно: открыть наш же главный экран.
            OPEN_MAIN_APP,
            // Свой PendingIntent статуса PackageInstaller: доставляет система, подпись и
            // nonce неприменимы (PI живёт дольше 30-секундного окна и стреляет повторно,
            // nonce одноразовый -- оба гейта рвали бы доставку статуса, замер Фазы 19 на
            // AOSP 16). Результат уходит только в наш биндер из pending-реестра; единственная
            // опасная ветка -- запуск EXTRA_INTENT при PENDING_USER_ACTION -- гейтится
            // списком системных пакетов в PackageInstallFlow.isSystemInstallerIntent.
            PACKAGEINSTALLER_CALLBACK,
        )

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS = listOf(
            INSTALL_PACKAGE,
            UNINSTALL_PACKAGE,
            UNFREEZE_AND_LAUNCH,
            UNFREEZE_APP
        )

        private const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3
        internal const val REQUEST_ANTI_SPY_VPN = 4

        private var hasRequestedPermission = false

        fun registerSameProcessRequest(intent: Intent) {
            intent.putExtra("same_process_nonce", SameProcessTokens.issue())
        }

        private fun checkSameProcessRequest(intent: Intent): Boolean {
            if (intent.action !in ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS) return false
            return SameProcessTokens.consume(intent.getStringExtra("same_process_nonce"))
        }
    }
}
