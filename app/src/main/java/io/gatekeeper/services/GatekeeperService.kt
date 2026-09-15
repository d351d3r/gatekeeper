package io.gatekeeper.services

import android.app.Activity
import android.app.ActivityManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import io.gatekeeper.R
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.CaCertificates
import io.gatekeeper.util.CloneOutcome
import io.gatekeeper.util.FileProviderProxy
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.UriForwardProxy
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.PackageSequence
import io.gatekeeper.util.PermissionGroups
import io.gatekeeper.util.StatusNotification
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkPackageWatcher
import io.gatekeeper.util.WorkProfilePolicy
import io.gatekeeper.util.VpnTunnelDetector

class GatekeeperService : Service() {
    private var policyManager: DevicePolicyManager? = null
    private var isProfileOwner = false
    private var packageManager: PackageManager? = null
    private var adminComponent: ComponentName? = null
    private var startActivityProxy: IStartActivityProxy? = null

    private val binder = object : IGatekeeperService.Stub() {
        override fun ping() {
        }

        override fun stopGatekeeperService(kill: Boolean) {
            Thread {
                try {
                    Thread.sleep(1)
                } catch (_: Exception) {
                }

                (application as GatekeeperApplication).unbindMainService()

                if (kill && !(isProfileOwner && FreezeService.hasPendingAppToFreeze())) {
                    if (isProfileOwner) {
                        // Keep work process alive for Anti Spy VPN monitoring.
                        return@Thread
                    }
                    System.exit(0)
                }
            }.start()
        }

        override fun getApps(callback: IGetAppsCallback, showAll: Boolean) {
            Thread {
                // Список открыли -- заодно догоняем пакеты, поставленные в профиль
                // мимо нас: манифест-ресивер PACKAGE_ADDED не доставляется (F3).
                if (isProfileOwner) {
                    WorkPackageWatcher.scan(this@GatekeeperService)
                }
                val pmFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                // canLaunch считаем один раз и несём в wrapper: подсказке C1 нужен
                // фактический критерий доступности (FLAG_INSTALLED + launcher) даже
                // в режиме showAll, где фильтр раньше его не вычислял.
                // Скрытость спрашивается у DPM по одному пакету за вызов, поэтому она
                // попадает в запись один раз: раньше тот же пакет опрашивался дважды --
                // в фильтре и в сборке wrapper.
                data class ScanEntry(
                    val info: ApplicationInfo,
                    val canLaunch: Boolean,
                    val isHidden: Boolean,
                )

                val list = packageManager!!.getInstalledApplications(pmFlags)
                    .asSequence()
                    .filter { it.packageName != packageName }
                    .map { info ->
                        ScanEntry(
                            info,
                            packageManager!!.getLaunchIntentForPackage(info.packageName) != null,
                            isHidden(info.packageName),
                        )
                    }
                    .filter { (info, canLaunch, isHidden) ->
                        val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        val isInstalled = info.flags and ApplicationInfo.FLAG_INSTALLED != 0
                        showAll || (!isSystem && isInstalled) || isHidden || canLaunch
                    }
                    .map { (info, canLaunch, isHidden) ->
                        ApplicationInfoWrapper(info)
                            .setCanLaunch(canLaunch)
                            .loadLabel(packageManager!!)
                            .setHidden(isHidden)
                    }
                    .sortedWith { x, y ->
                        when {
                            x.isHidden() && !y.isHidden() -> -1
                            !x.isHidden() && y.isHidden() -> 1
                            else -> x.getLabel()!!.compareTo(y.getLabel()!!, ignoreCase = true)
                        }
                    }
                    .toList()

                try {
                    callback.callback(list)
                } catch (_: RemoteException) {
                }
            }.start()
        }

        override fun loadIcon(info: ApplicationInfoWrapper, callback: ILoadIconCallback) {
            Thread {
                val icon = Utility.drawableToBitmap(
                    info.getInfo()!!.loadUnbadgedIcon(packageManager!!),
                    LIST_ICON_MAX_PX,
                )
                try {
                    callback.callback(icon)
                } catch (_: RemoteException) {
                }
            }.start()
        }

        override fun installApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            if (!app.isSystem()) {
                installUserApp(app, callback)
            } else {
                installSystemApp(app, callback)
            }
        }

        private fun installUserApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            // Честный отказ до запуска сессии: пакет уже стоит в этом профиле
            // (в том числе заморожен/скрыт) -- повторное клонирование объяснимо
            // и мгновенно, вместо криптичного отказа PackageInstaller.
            if (isPackageInstalledHere(app.getPackageName())) {
                callback.callback(CloneOutcome.RESULT_ALREADY_IN_PROFILE)
                return
            }
            val intent = Intent(DummyActivity.INSTALL_PACKAGE)
            intent.component = ComponentName(this@GatekeeperService, DummyActivity::class.java)
            intent.putExtra("package", app.getPackageName())
            intent.putExtra("apk", app.getSourceDir())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra("split_apks", app.getSplitApks())
            }

            val callbackExtra = Bundle()
            callbackExtra.putBinder("callback", callback.asBinder())
            intent.putExtra("callback", callbackExtra)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DummyActivity.registerSameProcessRequest(intent)
            startActivityProxy?.startActivity(intent)
        }

        private fun installSystemApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            if (!isProfileOwner) {
                callback.callback(CloneOutcome.RESULT_CANNOT_INSTALL_SYSTEM_APP)
                return
            }
            // enableSystemApp молчит о результате: если прошивка не отдаёт пакет
            // в профиль (класс Galaxy Store), его просто не будет после вызова.
            // Проверяем фактическое наличие -- это отказ, а не молчаливый "успех".
            policyManager!!.enableSystemApp(adminComponent!!, app.getPackageName())
            if (!isPackageInstalledHere(app.getPackageName())) {
                // E-1: часть прошивок (Samsung) не отдаёт system-пакет через
                // enableSystemApp. installExistingPackage (API 28+) ставит уже
                // установленный в другом профиле пакет в этот -- кандидат-фикс;
                // факт всё равно проверяем по наличию.
                if (!installExistingSystemApp(app.getPackageName())) {
                    callback.callback(CloneOutcome.RESULT_CANNOT_INSTALL_SYSTEM_APP)
                    return
                }
            }
            policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), false)
            callback.callback(Activity.RESULT_OK)
        }

        override fun installApk(uriForwarder: UriForwardProxy, callback: IAppInstallCallback) {
            val intent = Intent(DummyActivity.INSTALL_PACKAGE)
            intent.component = ComponentName(this@GatekeeperService, DummyActivity::class.java)
            val uri: Uri = FileProviderProxy.setUriForwardProxy(uriForwarder, "apk")
            intent.putExtra("direct_install_apk", uri)

            val callbackExtra = Bundle()
            callbackExtra.putBinder("callback", callback.asBinder())
            intent.putExtra("callback", callbackExtra)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DummyActivity.registerSameProcessRequest(intent)
            startActivityProxy?.startActivity(intent)
        }

        override fun uninstallApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            if (!app.isSystem()) {
                val intent = Intent(DummyActivity.UNINSTALL_PACKAGE)
                intent.component = ComponentName(this@GatekeeperService, DummyActivity::class.java)
                intent.putExtra("package", app.getPackageName())

                val callbackExtra = Bundle()
                callbackExtra.putBinder("callback", callback.asBinder())
                intent.putExtra("callback", callbackExtra)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                DummyActivity.registerSameProcessRequest(intent)
                startActivityProxy?.startActivity(intent)
            } else {
                if (isProfileOwner) {
                    policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), true)
                    callback.callback(Activity.RESULT_OK)
                } else {
                    callback.callback(CloneOutcome.RESULT_CANNOT_INSTALL_SYSTEM_APP)
                }
            }
        }

        override fun freezeApp(app: ApplicationInfoWrapper) {
            check(isProfileOwner) { "Cannot freeze app without being profile owner" }
            policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), true)
        }

        override fun unfreezeApp(app: ApplicationInfoWrapper) {
            check(isProfileOwner) { "Cannot unfreeze app without being profile owner" }
            policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), false)
        }

        override fun hasUsageStatsPermission(): Boolean =
            Utility.checkUsageStatsPermission(this@GatekeeperService)

        override fun hasSystemAlertPermission(): Boolean =
            Utility.checkSystemAlertPermission(this@GatekeeperService)

        override fun hasAllFileAccessPermission(): Boolean =
            Utility.checkAllFileAccessPermission()

        override fun getCrossProfileWidgetProviders(): List<String> {
            check(isProfileOwner) {
                "Cannot access cross-profile widget providers without being profile owner"
            }
            return policyManager!!.getCrossProfileWidgetProviders(adminComponent!!)
        }

        override fun setCrossProfileWidgetProviderEnabled(pkgName: String, enabled: Boolean): Boolean {
            check(isProfileOwner) {
                "Cannot access cross-profile widget providers without being profile owner"
            }
            return if (enabled) {
                policyManager!!.addCrossProfileWidgetProvider(adminComponent!!, pkgName)
            } else {
                policyManager!!.removeCrossProfileWidgetProvider(adminComponent!!, pkgName)
            }
        }

        override fun setStartActivityProxy(proxy: IStartActivityProxy) {
            startActivityProxy = proxy
        }

        override fun getCrossProfilePackages(): List<String> {
            check(isProfileOwner) {
                "Cannot access cross-profile packages without being profile owner"
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException(
                    "Cross-profile packages support is only available on Android 11 and later"
                )
            }
            return ArrayList(policyManager!!.getCrossProfilePackages(adminComponent!!))
        }

        override fun setCrossProfilePackages(packages: List<String>) {
            check(isProfileOwner) {
                "Cannot access cross-profile packages without being profile owner"
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException(
                    "Cross-profile packages support is only available on Android 11 and later"
                )
            }
            policyManager!!.setCrossProfilePackages(adminComponent!!, HashSet(packages))
        }

        /**
         * Спрашивается личным профилем: покрывает ли туннель трафик этого пользователя.
         * Из личного профиля возможности сети рабочего профиля не видны, а `tun0` виден
         * из обоих и ответа не дает.
         */
        override fun isDefaultNetworkTunneled(): Boolean =
            VpnTunnelDetector.isDefaultNetworkTunneled(this@GatekeeperService)

        override fun isIgnoringBatteryOptimizations(): Boolean =
            getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) ?: false

        override fun isBackgroundRestricted(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false)

        /**
         * Корневой CA ставится ТОЛЬКО в этот профиль: вызов проходит через биндер у
         * сервиса рабочего профиля, владелец которого мы и есть. Личный профиль эти
         * вызовы не видит. null -- успех, иначе текст ошибки для показа пользователю.
         */
        override fun installCaCertificate(cert: ByteArray): String? {
            if (!isProfileOwner) return "not profile owner"
            val parsed = CaCertificates.parse(cert) ?: return "not a certificate"
            return try {
                policyManager!!.installCaCert(adminComponent!!, parsed.encoded)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }

        override fun getInstalledCaCertificates(): List<String> {
            if (!isProfileOwner) return emptyList()
            return try {
                policyManager!!.getInstalledCaCerts(adminComponent!!).mapNotNull { bytes ->
                    val cert = CaCertificates.parse(bytes) ?: return@mapNotNull null
                    CaCertificates.encodeInfo(CaCertificates.infoOf(cert))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        override fun removeCaCertificate(sha256Fingerprint: String): Boolean {
            if (!isProfileOwner) return false
            return try {
                policyManager!!.getInstalledCaCerts(adminComponent!!).any { bytes ->
                    val cert = CaCertificates.parse(bytes) ?: return@any false
                    if (CaCertificates.sha256Hex(cert.encoded)
                        .equals(sha256Fingerprint, ignoreCase = true)
                    ) {
                        policyManager!!.uninstallCaCert(adminComponent!!, cert.encoded)
                        true
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * C2: кросс-профильные фильтры ссылок (docs/feature_cross_profile_links.md).
         * Только профиль-владелец может их ставить; личный профиль здесь
         * получает false. Сами фильтры строит WorkProfilePolicy.enforceWorkProfilePolicies
         * из префов — этот метод лишь персистит список и дёргает enforce,
         * чтобы набор фильтров оставался целым (случайный clear тут стирал бы
         * весь служебный релей между профилями). false = DPM отказал, префы
         * откачены, правило не выглядит применённым.
         */
        override fun setCrossProfileLinkRules(rules: List<String>): Boolean {
            val dpm = if (isProfileOwner) policyManager else null
            if (dpm == null) return false
            val local = LocalStorageManager.getInstance()
            val old = local.getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
            val new = rules.toSet()
            local.setStringList(
                LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES,
                new.toTypedArray(),
            )
            return try {
                WorkProfilePolicy.enforceWorkProfilePolicies(this@GatekeeperService)
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "setCrossProfileLinkRules: DPM refused link rules", e)
                local.setStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES, old)
                false
            }
        }

        /**
         * Приложения профиля, умеющие быть VPN: у них есть сервис с
         * android.net.VpnService. Список нужен личной стороне, чтобы предложить
         * выбор, а спросить его она может только здесь -- пакеты профиля видны
         * только изнутри профиля. Кодировка "пакет|имя", как у сертификатов.
         */
        override fun getVpnCapableApps(): List<String> {
            check(isProfileOwner) { "Cannot list VPN apps without being profile owner" }
            val pm = packageManager!!
            return pm.queryIntentServices(Intent(VPN_SERVICE_ACTION), PackageManager.GET_META_DATA)
                .filter { it.serviceInfo != null }
                // Приложение вправе объявить, что always-on оно не поддерживает.
                // Отсеиваем такие заранее: иначе пользователь выбирает вариант,
                // который система все равно отвергнет.
                .filter {
                    it.serviceInfo.metaData?.getBoolean(VPN_SUPPORTS_ALWAYS_ON, true) ?: true
                }
                .mapNotNull { it.serviceInfo.applicationInfo }
                // Свой AntiSpyDummyVpnService -- служебная заглушка, которой сторож
                // сбивает чужой туннель. Закреплять ее как защиту профиля бессмысленно.
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
                .map { it.packageName + "|" + pm.getApplicationLabel(it) }
                .sortedBy { it.substringAfter("|").lowercase() }
        }

        /** "" -- не закреплен; иначе "пакет|lockdown" (true/false). */
        override fun getAlwaysOnVpnState(): String {
            check(isProfileOwner) { "Cannot read always-on VPN without being profile owner" }
            val pkg = policyManager!!.getAlwaysOnVpnPackage(adminComponent!!) ?: return ""
            val lockdown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                policyManager!!.isAlwaysOnVpnLockdownEnabled(adminComponent!!)
            return "$pkg|$lockdown"
        }

        /**
         * null -- успех. Иначе текст отказа: пакет не умеет always-on, его нет в
         * профиле, или прошивка не дает закрепить туннель.
         */
        /**
         * Забрать новые пакеты профиля и очистить очередь (F3). Забирает личная
         * сторона: список автозаморозки хранится у неё. Реле для этого не годится --
         * на Android 14+ запуск activity из фонового сервиса рубит Background
         * Activity Launch, даже через AlarmManager (замерено на AVD 16).
         */
        /** Дешевая замена перечислению списка для наблюдателя с личной стороны. */
        override fun getPackageChangeSequence(since: Int): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return PackageSequence.UNSUPPORTED
            }
            val from = since.coerceAtLeast(PackageSequence.FROM_SCRATCH)
            return packageManager!!.getChangedPackages(from)?.sequenceNumber ?: from
        }

        override fun takeNewWorkPackages(): List<String> {
            check(isProfileOwner) { "Only the work profile tracks new packages" }
            val storage = LocalStorageManager.getInstance()
            val pending = storage.getStringList(LocalStorageManager.PREF_PENDING_NEW_PACKAGES)
            if (pending.isNotEmpty()) {
                storage.setStringList(LocalStorageManager.PREF_PENDING_NEW_PACKAGES, emptyArray())
            }
            return pending.toList()
        }

        override fun getDeniablePermissions(pkg: String): List<String> {
            if (!isProfileOwner) return emptyList()
            // Замороженное приложение скрыто (setApplicationHidden) и голому
            // getPackageInfo невидимо -- те же флаги, что и при перечислении списка,
            // иначе у замороженных приложений разрешения читались бы как пустые.
            val flags = PackageManager.GET_PERMISSIONS or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            val declared = try {
                packageManager!!.getPackageInfo(pkg, flags)
                    .requestedPermissions?.toSet() ?: emptySet()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "getDeniablePermissions: $pkg not installed here", e)
                emptySet()
            }
            return PermissionGroups.controllable().filter { it in declared }
        }

        override fun getPermissionState(pkg: String, permission: String): Int {
            if (!isProfileOwner) {
                return DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            }
            return try {
                policyManager!!.getPermissionGrantState(adminComponent!!, pkg, permission)
            } catch (e: SecurityException) {
                Log.w(TAG, "getPermissionState refused: $pkg/$permission", e)
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            }
        }

        override fun setPermissionState(pkg: String, permission: String, state: Int): Boolean {
            if (!isProfileOwner) return false
            return try {
                policyManager!!.setPermissionGrantState(adminComponent!!, pkg, permission, state)
            } catch (e: SecurityException) {
                Log.w(TAG, "setPermissionState refused: $pkg/$permission -> $state", e)
                false
            }
        }

        override fun getAppDataUsage(pkg: String): LongArray {
            val uid = if (isProfileOwner) uidOf(pkg) else null
            val nsm = this@GatekeeperService.getSystemService(NetworkStatsManager::class.java)
            return if (uid == null || nsm == null) longArrayOf(0L, 0L) else sumUidUsage(nsm, uid)
        }

        override fun setAlwaysOnVpn(packageName: String?, lockdown: Boolean): String? {
            check(isProfileOwner) { "Cannot set always-on VPN without being profile owner" }
            val target = packageName?.takeIf { it.isNotEmpty() }
            return try {
                policyManager!!.setAlwaysOnVpnPackage(adminComponent!!, target, lockdown)
                null
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "always-on VPN refused: $target", e)
                e.message ?: target.orEmpty()
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "always-on VPN unsupported", e)
                e.message ?: "unsupported"
            } catch (e: SecurityException) {
                Log.w(TAG, "always-on VPN denied", e)
                e.message ?: "denied"
            }
        }
    }

    private fun installExistingSystemApp(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            policyManager!!.installExistingPackage(adminComponent!!, packageName) &&
                isPackageInstalledHere(packageName)
        } catch (e: SecurityException) {
            Log.w(TAG, "installExistingPackage refused for $packageName", e)
            false
        }
    }

    override fun onCreate() {
        policyManager = getSystemService(DevicePolicyManager::class.java)
        packageManager = getPackageManager()
        isProfileOwner = policyManager!!.isProfileOwnerApp(packageName)
        adminComponent = ComponentName(applicationContext, GatekeeperDeviceAdminReceiver::class.java)
    }

    override fun onBind(intent: Intent?): IBinder {
        if (intent?.getBooleanExtra("foreground", false) == true) {
            setForeground()
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        return false
    }

    private fun isHidden(packageName: String): Boolean {
        return isProfileOwner &&
            policyManager!!.isApplicationHidden(adminComponent!!, packageName)
    }

    private fun uidOf(pkg: String): Int? = try {
        packageManager!!.getApplicationInfo(pkg, 0).uid
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "getAppDataUsage: $pkg not installed here", e)
        null
    }

    /** Суммарный трафик uid по Wi-Fi и мобильной сети за окно: [rxBytes, txBytes]. */
    private fun sumUidUsage(nsm: NetworkStatsManager, uid: Int): LongArray {
        val end = System.currentTimeMillis()
        val start = end - USAGE_WINDOW_MS
        var rx = 0L
        var tx = 0L
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val bytes = queryUidBytes(nsm, type, uid, start, end)
            rx += bytes[0]
            tx += bytes[1]
        }
        return longArrayOf(rx, tx)
    }

    private fun queryUidBytes(
        nsm: NetworkStatsManager,
        type: Int,
        uid: Int,
        start: Long,
        end: Long,
    ): LongArray {
        var rx = 0L
        var tx = 0L
        try {
            nsm.queryDetailsForUid(type, null, start, end, uid).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rx += bucket.rxBytes
                    tx += bucket.txBytes
                }
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "getAppDataUsage query failed type=$type", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "getAppDataUsage denied type=$type", e)
        }
        return longArrayOf(rx, tx)
    }

    /** Установлен ли пакет в профиле, где работает этот сервис (скрытые -- тоже установлены). */
    private fun isPackageInstalledHere(packageName: String): Boolean = try {
        packageManager!!.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun setForeground() {
        if (isProfileOwner) {
            WorkPackageWatcher.scan(this)
        }
        startForeground(
            NOTIFICATION_ID,
            Notifications.buildNotification(
                this,
                StatusNotification(
                    getString(R.string.app_name),
                    getString(R.string.service_title),
                    getString(R.string.service_desc),
                    R.drawable.ic_notification,
                ),
            ),
        )
        if (isProfileOwner) {
            AntiSpyVpnWatchService.syncState(this)
        }
    }

    companion object {
        private const val TAG = "GatekeeperService"
        private const val VPN_SERVICE_ACTION = "android.net.VpnService"
        private const val VPN_SUPPORTS_ALWAYS_ON = "android.net.VpnService.SUPPORTS_ALWAYS_ON"
        private const val NOTIFICATION_ID = 0x49a11
        private const val LIST_ICON_MAX_PX = 128

        /** Окно подсчёта трафика приложения: последние 30 дней. */
        private const val USAGE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
