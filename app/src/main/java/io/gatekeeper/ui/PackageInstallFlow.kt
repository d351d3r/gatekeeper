@file:Suppress("DEPRECATION") // legacy version-gated paths (pre-Q package installer/uninstaller)

package io.gatekeeper.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.os.StrictMode
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import io.gatekeeper.R
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.util.FileProviderProxy
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.InstallWarnPolicy
import io.gatekeeper.util.InstallationProgressListener
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.PendingIntents
import io.gatekeeper.util.PendingOperationRegistry
import io.gatekeeper.util.UnfreezeShortcuts
import io.gatekeeper.util.Utility
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Поток установки/удаления пакетов: entry points, legacy pre-Q путь и финал операции.
 * Сессионный стейджинг (API 21+) -- в [PackageInstallerSession]; состояние ожидающих
 * операций -- в [PendingPackageOperations]: колбэки прилетают в новую инстанцию activity.
 */
class PackageInstallFlow(
    private val activity: DummyActivity,
    private val isProfileOwner: Boolean,
) {
    private val intent get() = activity.intent

    fun installPackage() {
        val operationId = capturePendingPackageOperation(PendingPackageOperations.OperationType.INSTALL)
        // MIUI/HyperOS: сессия установки в рабочем профиле зависает на экране
        // установщика. Предупреждаем до запуска сессии; отмена отвечает вызвавшему
        // RESULT_CANCELED, а не молчаливым висяком (4PDA #2105, #2113).
        if (InstallWarnPolicy.shouldWarn(Utility.isMIUI(), isProfileOwner)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.miui_install_apk_warning)
                .setPositiveButton(R.string.continue_anyway) { _, _ ->
                    proceedInstallPackage(operationId)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    val pending = operationId?.let(PendingPackageOperations::consume)
                    FileProviderProxy.clearForwardProxy(pending?.forwardedUri)
                    try {
                        pending?.callback?.callback(Activity.RESULT_CANCELED)
                    } catch (_: RemoteException) {
                    }
                }
                .show()
            return
        }
        proceedInstallPackage(operationId)
    }

    fun uninstallPackage() {
        val operationId = capturePendingPackageOperation(PendingPackageOperations.OperationType.UNINSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uninstallPackageQ(operationId)
            return
        }

        val uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(
            uninstallIntent,
            PendingPackageOperations.registerLegacy(operationId)
        )
    }

    /** Результат legacy startActivityForResult: доставляем его ожидающей операции. */
    fun onLegacyActivityResult(resultCode: Int, requestCode: Int) {
        val operationId = PendingPackageOperations.consumeLegacy(requestCode)
        if (requestCode == PendingPackageOperations.REQUEST_INSTALL_PACKAGE || operationId != null) {
            appInstallFinished(resultCode, operationId)
        }
    }

    /** Колбэк сессии PackageInstaller (доставляет система через наш PendingIntent). */
    fun handleCallback(callbackIntent: Intent) {
        val operationId = callbackIntent.getStringExtra(PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID)
        val status = callbackIntent.extras!!.getInt(PackageInstaller.EXTRA_STATUS)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Единственная опасная ветка при бесподписном действии: запуск чужого
                // интента. Пускаем только системный экран подтверждения установки.
                @Suppress("DEPRECATION")
                val confirmIntent = callbackIntent.extras!!.get(Intent.EXTRA_INTENT) as? Intent
                val resolvedPkg = confirmIntent?.resolveActivity(activity.packageManager)?.packageName
                val systemInstaller = resolvedPkg == "android" ||
                    resolvedPkg == activity.packageName ||
                    resolvedPkg == "com.android.packageinstaller" ||
                    resolvedPkg == "com.google.android.packageinstaller"
                if (confirmIntent == null || !systemInstaller) {
                    Log.w(TAG, "PACKAGEINSTALLER_CALLBACK: чужой EXTRA_INTENT отклонён")
                    return
                }
                activity.startActivity(confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> appInstallFinished(Activity.RESULT_OK, operationId)
            else -> appInstallFinished(Activity.RESULT_CANCELED, operationId)
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
                uri = Uri.fromFile(java.io.File(intent.getStringExtra("apk")!!))
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
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.install_unknown_sources_title)
                    .setMessage(R.string.install_unknown_sources_message)
                    .setPositiveButton(R.string.install_unknown_sources_open) { _, _ ->
                        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.fromParts("package", activity.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            activity.startActivity(settings)
                        } catch (_: ActivityNotFoundException) {
                            GatekeeperToast.show(activity, R.string.power_diagnostics_settings_unavailable)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            try {
                PackageInstallerSession(activity, isProfileOwner)
                    .create(uri, intent.getStringArrayExtra("split_apks"), operationId)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        } else {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(
                installIntent,
                PendingPackageOperations.registerLegacy(operationId)
            )
        }

        StrictMode.setVmPolicy(policy)
    }

    private fun uninstallPackageQ(operationId: String?) {
        val pi = activity.packageManager.packageInstaller
        val callbackIntent = Intent(activity, DummyActivity::class.java).apply {
            action = DummyActivity.PACKAGEINSTALLER_CALLBACK
            operationId?.let { putExtra(PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID, it) }
            data = Uri.parse("gatekeeper://package-uninstall/${operationId ?: UUID.randomUUID()}")
        }
        val pendingIntent = PackageInstallerSession.callbackPendingIntent(
            activity, operationId?.hashCode() ?: 0, callbackIntent
        )
        pi.uninstall(requireNotNull(intent.getStringExtra("package")), pendingIntent.intentSender)
    }

    /** Финал любой операции установки/удаления: колбэк вызвавшему и summary-обработка. */
    fun appInstallFinished(resultCode: Int, operationId: String?) {
        val pending = operationId?.let(PendingPackageOperations::consume)
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
        val operationType = pending?.type ?: when (intent.action) {
            DummyActivity.INSTALL_PACKAGE -> PendingPackageOperations.OperationType.INSTALL
            DummyActivity.UNINSTALL_PACKAGE -> PendingPackageOperations.OperationType.UNINSTALL
            else -> null
        }

        if (resultCode == Activity.RESULT_OK && packageName != null && operationType != null) {
            onPackageOperationFinished(operationType, packageName)
        }

        try {
            callback?.callback(resultCode)
        } catch (_: RemoteException) {
        }

        activity.finish()
    }

    private fun onPackageOperationFinished(
        type: PendingPackageOperations.OperationType,
        packageName: String
    ) {
        when (type) {
            PendingPackageOperations.OperationType.INSTALL -> {
                grantPostNotificationsOnInstall(packageName)
            }
            PendingPackageOperations.OperationType.UNINSTALL -> {
                if (isProfileOwner) {
                    UnfreezeShortcuts.requestRemoveOnOtherProfile(activity, packageName)
                } else {
                    UnfreezeShortcuts.removeLauncherShortcutsEverywhere(activity, packageName)
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
            val dpm = activity.getSystemService(DevicePolicyManager::class.java) ?: return
            val admin = ComponentName(activity, GatekeeperDeviceAdminReceiver::class.java)
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

    private fun capturePendingPackageOperation(
        type: PendingPackageOperations.OperationType
    ): String? {
        if (!intent.hasExtra("callback")) {
            return null
        }
        val callbackExtra = intent.getBundleExtra("callback")!!
        val callback = IAppInstallCallback.Stub.asInterface(callbackExtra.getBinder("callback"))
        @Suppress("DEPRECATION")
        val forwardedUri = intent.getParcelableExtra<Uri>("direct_install_apk")
        val operationId = PendingPackageOperations.register(
            PendingPackageOperations.PendingPackageOperation(
                type = type,
                packageName = intent.getStringExtra("package"),
                callback = callback,
                forwardedUri = forwardedUri
            )
        )
        intent.putExtra(PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID, operationId)
        return operationId
    }

    private companion object {
        private const val TAG = "PackageInstallFlow"
    }
}

/**
 * Сессия PackageInstaller (API 21+): создание, стейджинг APK, коммит, откат.
 * Профиль-владелец ставит без пользовательского действия -- иначе массовое
 * восстановление из бэкапа (C4) превращается в N системных диалогов подряд.
 */
class PackageInstallerSession(
    private val activity: Activity,
    private val isProfileOwner: Boolean,
) {
    @Throws(IOException::class)
    fun create(uri: Uri?, splitApks: Array<String>?, operationId: String?) {
        val pi = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isProfileOwner) {
            params.setRequireUserAction(
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            )
        }
        val sessionId = pi.createSession(params)

        pi.registerSessionCallback(InstallationProgressListener(activity, pi, sessionId))

        val session = pi.openSession(sessionId)
        stageAll(uri, splitApks, session, operationId) {
            session.setStagingProgress(STAGING_PROGRESS_INITIAL)
            val callbackIntent = Intent(activity, DummyActivity::class.java).apply {
                action = DummyActivity.PACKAGEINSTALLER_CALLBACK
                operationId?.let {
                    putExtra(PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID, it)
                }
                data = Uri.parse("gatekeeper://package-installer/${operationId ?: sessionId}")
            }
            val pendingIntent = callbackPendingIntent(activity, sessionId, callbackIntent)
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun stageAll(
        baseUri: Uri?,
        splitApks: Array<String>?,
        session: PackageInstaller.Session,
        operationId: String?,
        commit: Runnable
    ) {
        val uris = ArrayList<Uri>()
        uris.add(baseUri!!)
        if (splitApks != null && splitApks.isNotEmpty()) {
            for (apk in splitApks) {
                uris.add(Uri.fromFile(java.io.File(apk)))
            }
        }

        Thread {
            var stagingFailed = false
            for (uri in uris) {
                try {
                    activity.contentResolver.openInputStream(uri).use { input ->
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
                abort(session, operationId)
                return@Thread
            }
            activity.runOnUiThread(commit)
        }.start()
    }

    /**
     * Откат сессии при ошибке стейджинга: сессия закрывается, ожидающая операция
     * потребляется и получает объяснимый отказ (INVALID: набор APK неполный/нечитаем).
     */
    private fun abort(session: PackageInstaller.Session, operationId: String?) {
        try {
            session.abandon()
        } catch (_: Exception) {
        }
        val pending = operationId?.let(PendingPackageOperations::consume)
        FileProviderProxy.clearForwardProxy(pending?.forwardedUri)
        try {
            pending?.callback?.callback(
                Activity.RESULT_FIRST_USER + PackageInstaller.STATUS_FAILURE_INVALID
            )
        } catch (_: RemoteException) {
        }
    }

    internal companion object {
        private const val TAG = "PackageInstallerSession"
        private const val STAGING_PROGRESS_INITIAL = 0.1f

        /**
         * PendingIntent статуса PackageInstaller отправляет из фона сама система; без
         * явного opt-in создателя BAL режет доставку и установка молча зависает
         * (замер Фазы 19 на AOSP 16). Общая логика -- PendingIntents.activity.
         */
        fun callbackPendingIntent(
            activity: Activity,
            requestCode: Int,
            callbackIntent: Intent
        ): PendingIntent = PendingIntents.activity(
            activity, requestCode, callbackIntent, PendingIntent.FLAG_MUTABLE
        )
    }
}

/**
 * Ожидающие install/uninstall операции и легаси request-code маппинг. Статус -- здесь,
 * в объекте: колбэк PackageInstaller прилетает системой в новую инстанцию activity.
 */
internal object PendingPackageOperations {
    const val REQUEST_INSTALL_PACKAGE = 1
    const val PENDING_PACKAGE_OPERATION_ID = "pending_package_operation_id"
    private const val MAX_LEGACY_REQUEST_CODE = 65_534

    private val registry = PendingOperationRegistry<PendingPackageOperation>()
    private val legacyOperationIds = ConcurrentHashMap<Int, String>()
    private val nextLegacyRequestCode =
        AtomicInteger(REQUEST_INSTALL_PACKAGE + 1)

    fun register(operation: PendingPackageOperation): String = registry.register(operation)

    fun consume(operationId: String): PendingPackageOperation? = registry.consume(operationId)

    fun registerLegacy(operationId: String?): Int {
        if (operationId == null) return REQUEST_INSTALL_PACKAGE
        val requestCode = nextLegacyRequestCode.getAndUpdate {
            if (it >= MAX_LEGACY_REQUEST_CODE) REQUEST_INSTALL_PACKAGE + 1 else it + 1
        }
        legacyOperationIds[requestCode] = operationId
        return requestCode
    }

    fun consumeLegacy(requestCode: Int): String? = legacyOperationIds.remove(requestCode)

    enum class OperationType {
        INSTALL, UNINSTALL
    }

    class PendingPackageOperation(
        val type: OperationType,
        val packageName: String?,
        val callback: IAppInstallCallback,
        val forwardedUri: Uri?
    )
}
