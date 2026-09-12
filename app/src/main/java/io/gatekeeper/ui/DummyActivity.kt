@file:Suppress("DEPRECATION") // legacy version-gated paths (pre-Q package installer/uninstaller)

package io.gatekeeper.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.StrictMode
import android.provider.Settings
import android.util.Log
import io.gatekeeper.util.GatekeeperToast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import io.gatekeeper.R
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.services.AntiSpyVpnWatchService
import io.gatekeeper.services.FreezeService
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.services.IFileShuttleService
import io.gatekeeper.services.IFileShuttleServiceCallback
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.AntiSpyVpnGuard
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.FileProviderProxy
import io.gatekeeper.util.InstallWarnPolicy
import io.gatekeeper.util.InstallationProgressListener
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.PendingIntents
import io.gatekeeper.util.PendingOperationRegistry
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.SameProcessTokens
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkProfileBatchFreeze
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DummyActivity : Activity() {
    private var isProfileOwner = false
    private var policyManager: DevicePolicyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        policyManager = getSystemService(DevicePolicyManager::class.java)
        isProfileOwner = policyManager!!.isProfileOwnerApp(packageName)
        if (isProfileOwner) {
            Utility.enforceWorkProfilePolicies(this)
            Utility.enforceUserRestrictions(this)
            SettingsManager.getInstance().applyAll()

            synchronized(DummyActivity::class.java) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasRequestedPermission
                    && FINALIZE_PROVISION != intent.action
                ) {
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

    /** Единственный гейт activity: своя подпись, свой процесс или публичное действие. */
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

        when (intent.action) {
            START_SERVICE -> actionStartService()
            TRY_START_SERVICE -> {
                setResult(RESULT_OK)
                finish()
            }
            INSTALL_PACKAGE -> actionInstallPackage()
            UNINSTALL_PACKAGE -> actionUninstallPackage()
            FINALIZE_PROVISION -> actionFinalizeProvision()
            UNFREEZE_AND_LAUNCH, PUBLIC_UNFREEZE_AND_LAUNCH -> actionUnfreezeAndLaunch()
            UNFREEZE_APP -> actionUnfreezeApp()
            PUBLIC_FREEZE_ALL -> actionPublicFreezeAll()
            PUBLIC_UNFREEZE_ALL -> actionPublicUnfreezeAll()
            SHOW_TOAST -> actionShowToast()
            REFRESH_MAIN_APP_LIST -> actionRefreshMainAppList()
            FREEZE_ALL_IN_LIST -> actionFreezeAllInList()
            UNFREEZE_ALL_IN_LIST -> actionUnfreezeAllInList()
            REMOVE_UNFREEZE_SHORTCUT -> actionRemoveUnfreezeShortcut()
            START_FILE_SHUTTLE, START_FILE_SHUTTLE_2 -> actionStartFileShuttle()
            SYNCHRONIZE_PREFERENCE -> actionSynchronizePreference()
            SYNC_ANTI_SPY_VPN_WATCH -> actionSyncAntiSpyVpnWatch()
            VPN_SESSION_COMPLETE -> actionVpnSessionComplete()
            OPEN_POWER_SETTINGS -> actionOpenPowerSettings()
            PACKAGEINSTALLER_CALLBACK -> handlePackageInstallerCallback(intent)
            else -> finish()
        }
    }

    private fun handlePackageInstallerCallback(callbackIntent: Intent) {
        val operationId = callbackIntent.getStringExtra(PENDING_PACKAGE_OPERATION_ID)
        val status = callbackIntent.extras!!.getInt(PackageInstaller.EXTRA_STATUS)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Единственная опасная ветка при бесподписном действии: запуск чужого
                // интента. Пускаем только системный экран подтверждения установки.
                @Suppress("DEPRECATION")
                val confirmIntent = callbackIntent.extras!!.get(Intent.EXTRA_INTENT) as? Intent
                if (confirmIntent == null || !isSystemInstallerIntent(confirmIntent)) {
                    Log.w(TAG, "PACKAGEINSTALLER_CALLBACK: чужой EXTRA_INTENT отклонён")
                    return
                }
                startActivity(confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> appInstallFinished(RESULT_OK, operationId)
            else -> appInstallFinished(RESULT_CANCELED, operationId)
        }
    }

    private fun isSystemInstallerIntent(intent: Intent): Boolean {
        val resolved = intent.resolveActivity(packageManager) ?: return false
        val pkg = resolved.packageName ?: return false
        return pkg == "android" ||
            pkg == packageName ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.google.android.packageinstaller"
    }

    private fun actionOpenPowerSettings() {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(details)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                GatekeeperToast.show(this, R.string.power_diagnostics_settings_unavailable)
            }
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isProfileOwner) {
            Utility.enforceWorkProfilePolicies(this)
            Utility.enforceUserRestrictions(this)
            SettingsManager.getInstance().applyAll()
        }
        init()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ANTI_SPY_VPN) {
            if (resultCode == RESULT_OK) {
                runAntiSpyLaunchGate()
            } else {
                showAntiSpyVpnPermissionDeniedDialog()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)

        val operationId = consumeLegacyOperationId(requestCode)
        if (requestCode == REQUEST_INSTALL_PACKAGE || operationId != null) {
            appInstallFinished(resultCode, operationId)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSION_EXTERNAL_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    doStartFileShuttle()
                } else {
                    finish()
                }
            }
            REQUEST_PERMISSION_POST_NOTIFICATIONS -> init()
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun actionFinalizeProvision() {
        if (isProfileOwner) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val intent = Intent(FINALIZE_PROVISION)
                if (Utility.tryTransferIntentToProfileUnsigned(this, intent)) {
                    startActivity(intent)
                }
            }
            finish()
        } else {
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            val intent = Intent(SetupWizardActivity.ACTION_PROFILE_PROVISIONED).apply {
                component = ComponentName(this@DummyActivity, SetupWizardActivity::class.java)
            }
            startActivity(intent)
            GatekeeperToast.show(this, getString(R.string.provision_finished), android.widget.Toast.LENGTH_LONG)
            finish()
        }
    }

    private fun actionStartService() {
        (application as GatekeeperApplication).bindMainService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val data = Intent()
                val bundle = Bundle().apply {
                    putBinder("service", service)
                }
                data.putExtra("extra", bundle)
                setResult(RESULT_OK, data)
                finish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // dummy
            }
        }, true)
    }

    private fun actionInstallPackage() {
        val operationId = capturePendingPackageOperation(OperationType.INSTALL)
        // MIUI/HyperOS: сессия установки в рабочем профиле зависает на экране
        // установщика. Предупреждаем до запуска сессии; отмена отвечает вызвавшему
        // RESULT_CANCELED, а не молчаливым висяком (4PDA #2105, #2113).
        if (InstallWarnPolicy.shouldWarn(Utility.isMIUI(), isProfileOwner)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.miui_install_apk_warning)
                .setPositiveButton(R.string.continue_anyway) { _, _ ->
                    proceedInstallPackage(operationId)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    cancelPendingInstall(operationId)
                }
                .show()
            return
        }
        proceedInstallPackage(operationId)
    }

    private fun cancelPendingInstall(operationId: String?) {
        val pending = operationId?.let(::consumePendingPackageOperation)
        FileProviderProxy.clearForwardProxy(pending?.forwardedUri)
        try {
            pending?.callback?.callback(Activity.RESULT_CANCELED)
        } catch (_: RemoteException) {
        }
    }

    private fun proceedInstallPackage(operationId: String?) {
        var uri: Uri? = null
        if (intent.hasExtra("package")) {
            uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        }
        val policy = StrictMode.getVmPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || intent.hasExtra("direct_install_apk")) {
            if (intent.hasExtra("apk")) {
                uri = Uri.fromFile(File(intent.getStringExtra("apk")!!))
            } else if (intent.hasExtra("direct_install_apk")) {
                uri = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, "direct_install_apk", Uri::class.java
                )
            }
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // AOSP 14+: сессия установки упирается в «unknown apps»-appop, без него
            // системный экран показывает отказ без шансов. Проверяем до сессии и
            // отводим пользователя в переключатель одним тапом (Фаза 19).
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.install_unknown_sources_title)
                    .setMessage(R.string.install_unknown_sources_message)
                    .setPositiveButton(R.string.install_unknown_sources_open) { _, _ ->
                        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.fromParts("package", packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            startActivity(settings)
                        } catch (_: ActivityNotFoundException) {
                            GatekeeperToast.show(this, R.string.power_diagnostics_settings_unavailable)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            try {
                actionInstallPackageQ(uri, intent.getStringArrayExtra("split_apks"), operationId)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        } else {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(installIntent, registerLegacyOperationId(operationId))
        }

        StrictMode.setVmPolicy(policy)
    }

    @Throws(IOException::class)
    private fun actionInstallPackageQ(
        uri: Uri?,
        splitApks: Array<String>?,
        operationId: String?
    ) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = pi.createSession(params)

        pi.registerSessionCallback(InstallationProgressListener(this, pi, sessionId))

        val session = pi.openSession(sessionId)
        doInstallPackageQ(uri, splitApks, session, operationId) {
            session.setStagingProgress(0.1f)
            val callbackIntent = Intent(this, DummyActivity::class.java).apply {
                action = PACKAGEINSTALLER_CALLBACK
                operationId?.let { putExtra(PENDING_PACKAGE_OPERATION_ID, it) }
                data = Uri.parse("gatekeeper://package-installer/${operationId ?: sessionId}")
            }
            val pendingIntent = packageInstallerCallbackPendingIntent(sessionId, callbackIntent)
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun doInstallPackageQ(
        baseUri: Uri?,
        splitApks: Array<String>?,
        session: PackageInstaller.Session,
        operationId: String?,
        callback: Runnable
    ) {
        val uris = ArrayList<Uri>()
        uris.add(baseUri!!)
        if (splitApks != null && splitApks.isNotEmpty()) {
            for (apk in splitApks) {
                uris.add(Uri.fromFile(File(apk)))
            }
        }

        Thread {
            var stagingFailed = false
            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        session.openWrite(UUID.randomUUID().toString(), 0, input!!.available().toLong())
                            .use { output ->
                                Utility.pipe(input, output)
                                session.fsync(output)
                            }
                    }
                } catch (e: IOException) {
                    // Фаза 19: непрочитанный APK (файл переехал, URI отозван) -- это отказ
                    // с объяснением, а не молчаливый коммит неполной сессии.
                    Log.w(TAG, "failed to stage $uri", e)
                    stagingFailed = true
                }
                if (stagingFailed) break
            }
            if (stagingFailed) {
                abortStaging(session, operationId)
                return@Thread
            }
            runOnUiThread(callback)
        }.start()
    }

    /**
     * PendingIntent статуса PackageInstaller отправляет из фона сама система; без
     * явного opt-in создателя BAL режет доставку и установка молча зависает
     * (замер Фазы 19 на AOSP 16). Общая логика -- [PendingIntents.activity].
     */
    private fun packageInstallerCallbackPendingIntent(
        requestCode: Int,
        callbackIntent: Intent
    ): PendingIntent = PendingIntents.activity(
        this, requestCode, callbackIntent, PendingIntent.FLAG_MUTABLE
    )

    /**
     * Откат сессии при ошибке стейджинга: сессия закрывается, ожидающая операция
     * потребляется и получает объяснимый отказ (INVALID: набор APK неполный/нечитаем).
     */
    private fun abortStaging(session: PackageInstaller.Session, operationId: String?) {
        try {
            session.abandon()
        } catch (_: Exception) {
        }
        val pending = operationId?.let(::consumePendingPackageOperation)
        FileProviderProxy.clearForwardProxy(pending?.forwardedUri)
        try {
            pending?.callback?.callback(
                Activity.RESULT_FIRST_USER + PackageInstaller.STATUS_FAILURE_INVALID
            )
        } catch (_: RemoteException) {
        }
    }

    private fun actionUninstallPackage() {
        val operationId = capturePendingPackageOperation(OperationType.UNINSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            actionUninstallPackageQ(operationId)
            return
        }

        val uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(uninstallIntent, registerLegacyOperationId(operationId))
    }

    private fun actionUninstallPackageQ(operationId: String?) {
        val pi = packageManager.packageInstaller
        val callbackIntent = Intent(this, DummyActivity::class.java).apply {
            action = PACKAGEINSTALLER_CALLBACK
            operationId?.let { putExtra(PENDING_PACKAGE_OPERATION_ID, it) }
            data = Uri.parse("gatekeeper://package-uninstall/${operationId ?: UUID.randomUUID()}")
        }
        val pendingIntent = packageInstallerCallbackPendingIntent(
            operationId?.hashCode() ?: 0, callbackIntent
        )
        pi.uninstall(requireNotNull(intent.getStringExtra("package")), pendingIntent.intentSender)
    }

    private fun appInstallFinished(resultCode: Int, operationId: String?) {
        val pending = operationId?.let(::consumePendingPackageOperation)
        FileProviderProxy.clearForwardProxy(pending?.forwardedUri)
        var callback: IAppInstallCallback? = null
        if (intent.hasExtra("callback")) {
            val callbackExtra = intent.getBundleExtra("callback")
            callback = IAppInstallCallback.Stub.asInterface(callbackExtra!!.getBinder("callback"))
        }
        if (callback == null) {
            callback = pending?.callback
        }

        val packageName = pending?.packageName ?: intent.getStringExtra("package")
        val operationType = pending?.type ?: operationTypeFromIntentAction(intent.action)

        if (resultCode == RESULT_OK && packageName != null && operationType != null) {
            onPackageOperationFinished(operationType, packageName)
        }

        try {
            callback?.callback(resultCode)
        } catch (_: RemoteException) {
        }

        finish()
    }

    private fun onPackageOperationFinished(type: OperationType, packageName: String) {
        when (type) {
            OperationType.INSTALL -> {
                grantPostNotificationsOnInstall(packageName)
            }
            OperationType.UNINSTALL -> {
                if (isProfileOwner) {
                    Utility.requestRemoveUnfreezeShortcutOnOtherProfile(this, packageName)
                } else {
                    Utility.removeUnfreezeLauncherShortcutsEverywhere(this, packageName)
                    LocalStorageManager.getInstance().removeFromStringList(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName
                    )
                }
            }
        }
    }

    /**
     * Клон в профиль: на Android 13+ свежеустановленное приложение не имеет
     * POST_NOTIFICATIONS, и уведомления (включая всплывающие звонки мессенджеров
     * вроде Max, 4PDA #1938) молча не приходят. Профиль-владелец может выдать
     * грант за пользователя; прошивка вправе отказать -- тогда остаётся
     * ручное разрешение в настройках профиля (см. USER_GUIDE).
     */
    private fun grantPostNotificationsOnInstall(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isProfileOwner) return
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
            val admin = ComponentName(this, GatekeeperDeviceAdminReceiver::class.java)
            dpm.setPermissionGrantState(
                admin,
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "grant POST_NOTIFICATIONS for $packageName failed", e)
        }
    }

    private fun capturePendingPackageOperation(type: OperationType): String? {
        if (!intent.hasExtra("callback")) {
            return null
        }
        val callbackExtra = intent.getBundleExtra("callback")!!
        val callback = IAppInstallCallback.Stub.asInterface(callbackExtra.getBinder("callback"))
        @Suppress("DEPRECATION")
        val forwardedUri = intent.getParcelableExtra<Uri>("direct_install_apk")
        val operationId = registerPendingPackageOperation(
            PendingPackageOperation(
                type = type,
                packageName = intent.getStringExtra("package"),
                callback = callback,
                forwardedUri = forwardedUri
            )
        )
        intent.putExtra(PENDING_PACKAGE_OPERATION_ID, operationId)
        return operationId
    }

    private fun operationTypeFromIntentAction(action: String?): OperationType? = when (action) {
        INSTALL_PACKAGE -> OperationType.INSTALL
        UNINSTALL_PACKAGE -> OperationType.UNINSTALL
        else -> null
    }

    private fun actionUnfreezeAndLaunch() {
        if (isProfileOwner && PUBLIC_UNFREEZE_AND_LAUNCH == intent.action) {
            if (AntiSpyVpnGuard.forwardPublicUnfreezeToParent(this, intent)) {
                finish()
                return
            }
        }

        if (!isProfileOwner) {
            if (intent.getStringExtra("packageName") == null) {
                finish()
                return
            }
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            val packageName = requireNotNull(intent.getStringExtra("packageName"))
            val proceed = Runnable {
                forwardUnfreezeAndLaunchToWorkProfile()
                finish()
            }
            if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
                runAntiSpyLaunchGate()
            } else {
                proceed.run()
            }
            return
        }

        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringArrayExtra("linkedPackages")!!
            val packagesShouldFreeze = intent.getBooleanArrayExtra("linkedPackagesShouldFreeze")!!

            for (i in packages.indices) {
                policyManager!!.setApplicationHidden(
                    ComponentName(this, GatekeeperDeviceAdminReceiver::class.java),
                    packages[i], false
                )
                if (packagesShouldFreeze[i]) {
                    registerAppToFreeze(packages[i])
                }
            }
        }

        val packageName = intent.getStringExtra("packageName")!!

        policyManager!!.setApplicationHidden(
            ComponentName(this, GatekeeperDeviceAdminReceiver::class.java),
            packageName, false
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            if (intent.getBooleanExtra("shouldFreeze", false)) {
                registerAppToFreeze(packageName)
            }
            startActivity(launchIntent)
        } else {
            GatekeeperToast.show(this, getString(R.string.launch_app_fail, packageName))
        }

        finish()
    }

    private fun ensureAntiSpyVpnPermissionThenLaunch(): Boolean {
        val storage = LocalStorageManager.getInstance()
        if (!AntiSpyLaunchGate.needsVpnClear(this, storage)) {
            return true
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            return true
        }
        @Suppress("DEPRECATION")
        startActivityForResult(prepare, REQUEST_ANTI_SPY_VPN)
        return false
    }

    private fun runAntiSpyLaunchGate() {
        AntiSpyLaunchGate.runBeforeLaunch(
            this, LocalStorageManager.getInstance(),
            requireNotNull(intent.getStringExtra("packageName")),
            {
                forwardUnfreezeAndLaunchToWorkProfile()
                finish()
            },
            { reason ->
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    if (ensureAntiSpyVpnPermissionThenLaunch()) {
                        runAntiSpyLaunchGate()
                    }
                    return@runBeforeLaunch
                }
                finish()
            }
        )
    }

    private fun showAntiSpyVpnPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.anti_spy_vpn_block_title)
            .setMessage(R.string.anti_spy_vpn_permission_required)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                if (ensureAntiSpyVpnPermissionThenLaunch()) {
                    runAntiSpyLaunchGate()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun forwardUnfreezeAndLaunchToWorkProfile() {
        val forwardIntent = Intent(UNFREEZE_AND_LAUNCH)
        if (!Utility.tryTransferIntentToProfile(this, forwardIntent)) return
        val packageName = requireNotNull(intent.getStringExtra("packageName"))
        forwardIntent.putExtra("packageName", packageName)
        forwardIntent.putExtra(
            "shouldFreeze",
            SettingsManager.getInstance().getAutoFreezeServiceEnabled() &&
                LocalStorageManager.getInstance()
                    .stringListContains(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName
                    )
        )
        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
            val packagesShouldFreeze = BooleanArray(packages.size)
            for (i in packages.indices) {
                packagesShouldFreeze[i] = SettingsManager.getInstance().getAutoFreezeServiceEnabled() &&
                    LocalStorageManager.getInstance()
                        .stringListContains(
                            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                            packages[i]
                        )
            }
            forwardIntent.putExtra("linkedPackages", packages)
            forwardIntent.putExtra("linkedPackagesShouldFreeze", packagesShouldFreeze)
        }
        startActivity(forwardIntent)
    }

    private fun registerAppToFreeze(packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        startService(Intent(this, FreezeService::class.java))
    }

    private fun finishBatchShortcutFlow() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun forwardBatchToMainActivityIfVisible(batchAction: String): Boolean {
        if (!MainActivity.isResumed) return false
        val mainAction = when (batchAction) {
            PUBLIC_FREEZE_ALL -> MainActivity.ACTION_BATCH_FREEZE_ALL
            PUBLIC_UNFREEZE_ALL -> MainActivity.ACTION_BATCH_UNFREEZE_ALL
            else -> return false
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = mainAction
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finishBatchShortcutFlow()
        return true
    }

    private fun actionShowToast() {
        val resId = intent.getIntExtra(MainActivity.EXTRA_TOAST_RES_ID, 0)
        if (resId != 0) {
            GatekeeperToast.show(this, resId)
        }
        if (!isProfileOwner) {
            Utility.deliverAppListRefreshInMainProcess(this)
            Utility.scheduleAppListRefresh(this, longArrayOf(700L, 2000L, 4500L))
        }
        finishBatchShortcutFlow()
    }

    private fun actionRefreshMainAppList() {
        if (!isProfileOwner) {
            Utility.deliverAppListRefreshInMainProcess(this)
            Utility.scheduleAppListRefresh(this, longArrayOf(700L, 2000L, 4500L))
        }
        finishBatchShortcutFlow()
    }

    private fun actionPublicFreezeAll() {
        if (!isProfileOwner) {
            if (forwardBatchToMainActivityIfVisible(PUBLIC_FREEZE_ALL)) return
            AntiSpyManager.syncAutoFreezeListToWorkProfile(this)
            Utility.launchFreezeInWorkProfile(this, AntiSpyManager.getAutoFreezeList(this))
            finishBatchShortcutFlow()
        } else {
            throw RuntimeException("unimplemented")
        }
    }

    private fun actionUnfreezeApp() {
        if (!isProfileOwner) {
            val packageName = intent.getStringExtra("packageName") ?: run {
                finish()
                return
            }
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            val proceed = Runnable {
                forwardUnfreezeAppToWorkProfile(packageName)
                finish()
            }
            if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
                AntiSpyLaunchGate.runBeforeLaunch(
                    this, LocalStorageManager.getInstance(), packageName,
                    proceed,
                    { reason ->
                        if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                            if (ensureAntiSpyVpnPermissionThenLaunch()) {
                                actionUnfreezeApp()
                            }
                            return@runBeforeLaunch
                        }
                        finish()
                    }
                )
            } else {
                proceed.run()
            }
            return
        }

        val packageName = intent.getStringExtra("packageName") ?: run {
            finish()
            return
        }
        policyManager!!.setApplicationHidden(
            ComponentName(this, GatekeeperDeviceAdminReceiver::class.java),
            packageName, false
        )
        Utility.scheduleAppListRefresh(this)
        finish()
    }

    private fun forwardUnfreezeAppToWorkProfile(packageName: String) {
        val forwardIntent = Intent(UNFREEZE_APP)
        if (!Utility.tryTransferIntentToProfile(this, forwardIntent)) return
        forwardIntent.putExtra("packageName", packageName)
        startActivity(forwardIntent)
    }

    private fun actionPublicUnfreezeAll() {
        if (!isProfileOwner) {
            if (forwardBatchToMainActivityIfVisible(PUBLIC_UNFREEZE_ALL)) return
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            AntiSpyLaunchGate.runBeforeLaunch(
                this, LocalStorageManager.getInstance(), "",
                {
                    val forwardIntent = Intent(UNFREEZE_ALL_IN_LIST)
                    if (Utility.tryTransferIntentToProfile(this, forwardIntent)) {
                        val list = LocalStorageManager.getInstance()
                            .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                        forwardIntent.putExtra("list", list)
                        startActivity(forwardIntent)
                    }
                    finishBatchShortcutFlow()
                },
                { reason ->
                    if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                        if (ensureAntiSpyVpnPermissionThenLaunch()) {
                            actionPublicUnfreezeAll()
                        }
                        return@runBeforeLaunch
                    }
                    finish()
                }
            )
        } else {
            throw RuntimeException("unimplemented")
        }
    }

    private fun actionFreezeAllInList() {
        if (isProfileOwner) {
            val list = intent.getStringArrayExtra("list") ?: run {
                finish()
                return
            }
            // Persist the authoritative list into the work profile so the work-profile VPN
            // watcher can freeze on its own when a VPN comes up later (it is the only context
            // privileged to call DevicePolicyManager; cross-profile starts from personal are denied).
            LocalStorageManager.getInstance()
                .setStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, list)
            AntiSpyVpnWatchService.syncState(this)
            val frozen = WorkProfileBatchFreeze.freezeList(this, list)
            val stillVisible = WorkProfileBatchFreeze.countStillVisible(this, list)
            if (stillVisible == 0) {
                Utility.notifyVpnBatchFreezeSessionComplete(this, frozen > 0)
            } else if (frozen > 0) {
                Utility.postVpnAutoFreezeSuccessAlert(this)
                Utility.showToastOnMainProfile(this, R.string.freeze_all_success)
            }
            Utility.scheduleAppListRefreshDelivery(this)
            finish()
        } else {
            finish()
        }
    }

    private fun actionUnfreezeAllInList() {
        if (isProfileOwner) {
            val list = intent.getStringArrayExtra("list")
            if (list != null) {
                val admin = ComponentName(this, GatekeeperDeviceAdminReceiver::class.java)
                for (pkg in list) {
                    if (pkg.isNullOrEmpty()) continue
                    policyManager!!.setApplicationHidden(admin, pkg, false)
                }
            }
            stopService(Intent(this, FreezeService::class.java))
            Utility.showToastOnMainProfile(this, R.string.unfreeze_all_success)
            finish()
        } else {
            finish()
        }
    }

    private fun actionStartFileShuttle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                doStartFileShuttle()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_PERMISSION_EXTERNAL_STORAGE
                )
            }
        } else {
            // "Поверх других приложений" здесь больше не нужно: шаттл запрашивают только
            // видимые activity, фонового старта в этом пути не осталось.
            if (Utility.checkAllFileAccessPermission()) {
                doStartFileShuttle()
            } else {
                Log.w(TAG, "file shuttle refused: no all-files access in this profile")
                finish()
            }
        }
    }

    private fun doStartFileShuttle() {
        (application as GatekeeperApplication).bindFileShuttleService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val shuttle = IFileShuttleService.Stub.asInterface(service)
                val callback = IFileShuttleServiceCallback.Stub.asInterface(
                    intent.getBundleExtra("extra")!!.getBinder("callback")
                )
                try {
                    callback.callback(shuttle)
                } catch (_: RemoteException) {
                }
                finish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Do Nothing
            }
        })
    }

    private fun actionSynchronizePreference() {
        val name = requireNotNull(intent.getStringExtra("name"))
        // Синхронно: настройки сторожа читает процесс :vpnwatch, который поднимается ниже.
        if (intent.hasExtra("boolean")) {
            LocalStorageManager.getInstance()
                .setBooleanNow(name, intent.getBooleanExtra("boolean", false))
        } else if (intent.hasExtra("int")) {
            LocalStorageManager.getInstance()
                .setIntNow(name, intent.getIntExtra("int", Int.MIN_VALUE))
        }
        SettingsManager.getInstance().applyAll()
        if (isProfileOwner) {
            Utility.enforceWorkProfilePolicies(this)
        }
        if (name.startsWith(ANTI_SPY_PREF_PREFIX)) {
            AntiSpyVpnWatchService.syncState(this)
        }
        finish()
    }

    private fun actionRemoveUnfreezeShortcut() {
        val packageName = intent.getStringExtra("packageName") ?: run {
            finish()
            return
        }
        Utility.removeUnfreezeLauncherShortcuts(this, packageName)
        if (!isProfileOwner) {
            LocalStorageManager.getInstance().removeFromStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        }
        finish()
    }

    private fun actionSyncAntiSpyVpnWatch() {
        if (isProfileOwner) {
            val storage = LocalStorageManager.getInstance()
            if (intent.hasExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)) {
                val list = intent.getStringArrayExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)
                if (list != null) {
                    storage.setStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, list)
                }
            }
            AntiSpyVpnWatchService.syncState(this)
        }
        finish()
    }

    /** Work profile → personal: stop both :vpnwatch poll loops after VPN batch-freeze. */
    private fun actionVpnSessionComplete() {
        if (!isProfileOwner) {
            Utility.deliverVpnBatchFreezeSessionComplete(this)
            finishBatchShortcutFlow()
        } else {
            finish()
        }
    }

    companion object {
        private const val TAG = "DummyActivity"

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
        const val START_FILE_SHUTTLE = ProfileActions.START_FILE_SHUTTLE
        const val START_FILE_SHUTTLE_2 = ProfileActions.START_FILE_SHUTTLE_2
        const val SYNCHRONIZE_PREFERENCE = ProfileActions.SYNCHRONIZE_PREFERENCE
        /** Ключи настроек сторожа VPN: их приезд обязан поднять или снять сторож. */
        private const val ANTI_SPY_PREF_PREFIX = "anti_spy_"
        const val SYNC_ANTI_SPY_VPN_WATCH =
            ProfileActions.SYNC_ANTI_SPY_VPN_WATCH
        const val VPN_SESSION_COMPLETE =
            ProfileActions.VPN_SESSION_COMPLETE
        const val PACKAGEINSTALLER_CALLBACK = ProfileActions.PACKAGEINSTALLER_CALLBACK
        const val OPEN_POWER_SETTINGS = ProfileActions.OPEN_POWER_SETTINGS

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE = listOf(
            FINALIZE_PROVISION,
            TRY_START_SERVICE,
            PUBLIC_FREEZE_ALL,
            PUBLIC_UNFREEZE_ALL,
            PUBLIC_UNFREEZE_AND_LAUNCH,
            REFRESH_MAIN_APP_LIST,
            SHOW_TOAST,
            // Свой PendingIntent статуса PackageInstaller: доставляет система, подпись и
            // nonce неприменимы (PI живёт дольше 30-секундного окна и стреляет повторно,
            // nonce одноразовый -- оба гейта рвали бы доставку статуса, замер Фазы 19 на
            // AOSP 16). Результат уходит только в наш биндер из pending-реестра; единственная
            // опасная ветка -- запуск EXTRA_INTENT при PENDING_USER_ACTION -- гейтится
            // списком системных пакетов в [isSystemInstallerIntent].
            PACKAGEINSTALLER_CALLBACK,
        )

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS = listOf(
            INSTALL_PACKAGE,
            UNINSTALL_PACKAGE,
            UNFREEZE_AND_LAUNCH,
            UNFREEZE_APP
        )

        private const val REQUEST_INSTALL_PACKAGE = 1
        private const val PENDING_PACKAGE_OPERATION_ID = "pending_package_operation_id"
        private const val REQUEST_PERMISSION_EXTERNAL_STORAGE = 2
        private const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3
        private const val REQUEST_ANTI_SPY_VPN = 4

        private var hasRequestedPermission = false
        private val pendingPackageOperations = PendingOperationRegistry<PendingPackageOperation>()
        private val legacyOperationIds = ConcurrentHashMap<Int, String>()
        private val nextLegacyRequestCode = AtomicInteger(REQUEST_INSTALL_PACKAGE + 1)

        private fun registerPendingPackageOperation(operation: PendingPackageOperation): String =
            pendingPackageOperations.register(operation)

        private fun consumePendingPackageOperation(operationId: String): PendingPackageOperation? =
            pendingPackageOperations.consume(operationId)

        private fun registerLegacyOperationId(operationId: String?): Int {
            if (operationId == null) return REQUEST_INSTALL_PACKAGE
            val requestCode = nextLegacyRequestCode.getAndUpdate {
                if (it >= 65_534) REQUEST_INSTALL_PACKAGE + 1 else it + 1
            }
            legacyOperationIds[requestCode] = operationId
            return requestCode
        }

        private fun consumeLegacyOperationId(requestCode: Int): String? =
            legacyOperationIds.remove(requestCode)

        private enum class OperationType {
            INSTALL, UNINSTALL
        }

        private data class PendingPackageOperation(
            val type: OperationType,
            val packageName: String?,
            val callback: IAppInstallCallback,
            val forwardedUri: Uri?
        )

        fun registerSameProcessRequest(intent: Intent) {
            intent.putExtra("same_process_nonce", SameProcessTokens.issue())
        }

        private fun checkSameProcessRequest(intent: Intent): Boolean {
            if (intent.action !in ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS) return false
            return SameProcessTokens.consume(intent.getStringExtra("same_process_nonce"))
        }
    }
}
