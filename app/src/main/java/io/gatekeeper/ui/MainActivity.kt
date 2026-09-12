@file:Suppress("DEPRECATION") // legacy version-gated paths (LocalBroadcastManager)

package io.gatekeeper.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.RemoteException
import android.os.UserManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import io.gatekeeper.util.GatekeeperToast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.services.IGetAppsCallback
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.services.IStartActivityProxy
import io.gatekeeper.services.KillerService
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.FileShuttleConnection
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.PowerDiagnostics
import io.gatekeeper.util.ServiceLiveness
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.StoreCloneHint
import io.gatekeeper.util.UriForwardProxy
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkServiceBindFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private val startSetup =
        registerForActivityResult(SetupWizardActivity.SetupWizardContract(), this::setupWizardCb)
    private val resumeSetup =
        registerForActivityResult(SetupWizardActivity.ResumeSetupContract(), this::setupWizardCb)
    private val selectApk =
        registerForActivityResult(
            Utility.ActivityResultContractInputWrapper(
                ActivityResultContracts.OpenDocument(),
                arrayOf("application/vnd.android.package-archive")
            ),
            this::onApkSelected
        )
    private val createBackup =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            this::onBackupLocationPicked
        )
    private val openBackup =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
            this::onBackupFilePicked
        )
    private val tryStartWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::tryStartWorkServiceCb)
    private val bindWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::bindWorkServiceCb)
    private val antiSpyVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                retryPendingLaunchAfterVpnPermission()
            } else {
                showAntiSpyVpnLaunchBlockedDialog(AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED)
            }
        }
    private val postNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                AntiSpyManager.syncVpnWatchEverywhere(this)
            }
        }

    private var storage: LocalStorageManager? = null
    private var restarting = false
    private var serviceMain: IGatekeeperService? = null
    private var serviceWork: IGatekeeperService? = null
    var showAll = false
    private var pendingVpnBlockReason = 0
    private var pendingLaunchPackageName: String? = null
    private var pendingBatchAction: String? = null
    private var pendingApkInstallAfterVpnGate = false
    private var workStartAttempts = 0
    private var workBindAttempts = 0
    private var pendingBindFailureReason = 0
    private var retryStartupProbeAfterFailure = false
    private var pendingDocumentsUi = false
    private var mainAppListFragment: AppListFragment? = null
    private var workAppListFragment: AppListFragment? = null
    private val workListPollHandler = Handler(Looper.getMainLooper())
    private var workPackageSnapshot: Set<String>? = null

    private val antiSpyVpnBlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getIntExtra(
                AntiSpyLaunchGate.EXTRA_BLOCK_REASON,
                AntiSpyLaunchGate.REASON_VPN_STILL_ACTIVE
            )
            val packageName = intent.getStringExtra(AntiSpyLaunchGate.EXTRA_PACKAGE_NAME)
            if (!TextUtils.isEmpty(packageName)) {
                pendingLaunchPackageName = packageName
            }
            pendingVpnBlockReason = reason
            showAntiSpyVpnLaunchBlockedDialog(reason)
        }
    }

    private val appListRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshAppLists()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        storage = LocalStorageManager.getInstance()

        if (getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)) {
            android.util.Log.d("MainActivity", "started in user profile. stopping.")
            finish()
            return
        }

        if (finishIfBackgroundShortcutLaunch(intent)) {
            return
        }

        setContentView(R.layout.activity_main)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(true)
            setDisplayUseLogoEnabled(false)
        }
        val logoInset = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        ).toInt()
        toolbar.setContentInsetsRelative(logoInset, 4)

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                antiSpyVpnBlockReceiver,
                IntentFilter(AntiSpyLaunchGate.BROADCAST_LAUNCH_BLOCKED_VPN)
            )
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                appListRefreshReceiver,
                IntentFilter(AppListFragment.BROADCAST_REFRESH)
            )

        init()
    }

    private fun finishIfBackgroundShortcutLaunch(intent: Intent?): Boolean {
        if (!isBackgroundShortcutAction(intent)) return false
        val s = storage!!
        if (!s.getBoolean(LocalStorageManager.PREF_HAS_SETUP)
            || s.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP)
        ) {
            return false
        }
        dispatchBackgroundShortcutAction(intent!!)
        finish()
        return true
    }

    private fun isBackgroundShortcutAction(intent: Intent?): Boolean =
        intent?.action in listOf(
            ACTION_BATCH_FREEZE_ALL,
            ACTION_BATCH_UNFREEZE_ALL,
            ACTION_SHOW_BATCH_TOAST,
            ACTION_REFRESH_APP_LISTS,
        )

    private fun dispatchBackgroundShortcutAction(intent: Intent) {
        when (intent.action) {
            ACTION_BATCH_FREEZE_ALL -> launchDummyBatch(DummyActivity.PUBLIC_FREEZE_ALL)
            ACTION_BATCH_UNFREEZE_ALL -> launchDummyBatch(DummyActivity.PUBLIC_UNFREEZE_ALL)
            ACTION_SHOW_BATCH_TOAST -> {
                val resId = intent.getIntExtra(EXTRA_TOAST_RES_ID, 0)
                if (resId != 0) {
                    GatekeeperToast.show(this, resId)
                }
                refreshAppLists()
            }
            ACTION_REFRESH_APP_LISTS -> refreshAppLists()
        }
    }

    private fun launchDummyBatch(action: String) {
        startActivity(Intent(action).apply {
            component = ComponentName(this@MainActivity, DummyActivity::class.java)
        })
    }

    private fun init() {
        val s = storage!!
        if (s.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) && !Utility.isWorkProfileAvailable(this)) {
            resumeSetup.launch(null)
        } else if (!s.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            // Подхват живого профиля до запуска мастера: если managed profile ещё
            // существует и маршрутизирует TRY_START_SERVICE обратно в Gatekeeper,
            // isWorkProfileAvailable сама выставит PREF_HAS_SETUP/PREF_IS_SETTING_UP
            // и восстановит управление без пересоздания профиля (4PDA #1404/#736:
            // иначе мастер упирается в «профиль уже существует» и пользователь
            // остаётся только с удалением профиля и потерей данных).
            if (Utility.isWorkProfileAvailable(this)) {
                init()
            } else {
                startSetup.launch(null)
            }
        } else {
            if (AntiSpyManager.shouldRunStartupFreeze(s)) {
                Utility.trimApplicationCache(this)
            }
            handleBatchShortcutIntent(intent)
            AntiSpyManager.onApplicationLaunch(s, BuildConfig.VERSION_CODE)
            requestAntiSpyNotificationPermissionIfNeeded()
            SettingsManager.getInstance().applyAll()
            bindServices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBatchShortcutIntent(intent)
    }

    private fun setupWizardCb(result: Boolean) {
        if (result) init() else finish()
    }

    private fun bindServices() {
        (application as GatekeeperApplication).bindMainService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                serviceMain = IGatekeeperService.Stub.asInterface(service)
                tryStartWorkService()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // dummy
            }
        }, false)
    }

    private fun tryStartWorkService() {
        val intent = Intent(DummyActivity.TRY_START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (_: IllegalStateException) {
            // Отказ резолва бывает транзиторным: clearCrossProfileIntentFilters уже прошел,
            // а повторная регистрация фильтров в enforceWorkProfilePolicies еще нет. Стирать
            // PREF_HAS_SETUP можно только убедившись, что профиля действительно нет, --
            // иначе приложение перестает управлять живым профилем и уводит в мастер.
            if (hasManagedProfile()) {
                showWorkServiceBindFailed(
                    WorkServiceBindFailure.NO_RESOLUTION,
                    retryStartupProbe = true
                )
            } else {
                storage!!.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                GatekeeperToast.show(this, getString(R.string.work_profile_not_found), android.widget.Toast.LENGTH_LONG)
                finish()
            }
            return
        }
        tryStartWorkService.launch(intent)
    }

    private fun hasManagedProfile(): Boolean {
        val userManager = getSystemService(UserManager::class.java) ?: return false
        return userManager.userProfiles.any { it != Process.myUserHandle() }
    }

    private fun tryStartWorkServiceCb(result: ActivityResult) {
        val resultOk = result.resultCode == RESULT_OK
        if (resultOk) {
            workStartAttempts = 0
            bindWorkService()
        } else if (WorkServiceBindFailure.shouldRetryStartupProbe(
                resultOk = resultOk,
                attemptsMade = workStartAttempts
            )
        ) {
            workStartAttempts++
            tryStartWorkService()
        } else {
            showWorkServiceBindFailed(
                WorkServiceBindFailure.CANCELLED,
                retryStartupProbe = true
            )
        }
    }

    private fun bindWorkService() {
        val intent = Intent(DummyActivity.START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            Utility.transferIntentToProfile(this, intent)
            bindWorkService.launch(intent)
        } catch (e: IllegalStateException) {
            onWorkServiceBindFailed(WorkServiceBindFailure.NO_RESOLUTION, e)
        } catch (e: ActivityNotFoundException) {
            onWorkServiceBindFailed(WorkServiceBindFailure.NO_RESOLUTION, e)
        }
    }

    private fun bindWorkServiceCb(result: ActivityResult) {
        val binder = result.data?.getBundleExtra("extra")?.getBinder("service")
        val reason = WorkServiceBindFailure.classify(result.resultCode == RESULT_OK, binder != null)
        if (reason != WorkServiceBindFailure.NONE) {
            onWorkServiceBindFailed(reason, null)
            return
        }
        workBindAttempts = 0
        serviceWork = IGatekeeperService.Stub.asInterface(binder)
        registerStartActivityProxies()
        startKiller()
        window.decorView.post {
            runAntiSpyStartupFreezeIfNeeded()
            AntiSpyManager.syncVpnWatchEverywhere(this@MainActivity)
            checkPowerDiagnosticsOnceDaily()
            runPendingBatchShortcutAction()
            startWorkListPolling()
        }
        buildView()
        if (!maybePromptStoreClone()) {
            maybeShowFileShuttleHint()
        }
    }

    /**
     * Одноразовая подсказка после настройки: магазина приложений нет в
     * рабочем профиле -- предлагаем клонировать одним тапом. Закрывает самый
     * частый вопрос новичков (4PDA #2097-#2118, #2100). Чистая логика
     * порогов -- в [StoreCloneHint]; здесь только binder-вызовы и UI.
     * @return true, если диалог будет показан (подсказки не наслаиваются).
     */
    private fun maybePromptStoreClone(): Boolean {
        val local = storage
        val main = serviceMain
        val work = serviceWork
        if (local == null || main == null || work == null) return false
        return promptStoreCloneIfDue(local, main, work)
    }

    private fun promptStoreCloneIfDue(
        local: LocalStorageManager,
        main: IGatekeeperService,
        work: IGatekeeperService,
    ): Boolean {
        if (!StoreCloneHint.isDue(
                local.getIntFresh(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                    StoreCloneHint.STATE_NEVER_ASKED,
                ),
                local.getLong(LocalStorageManager.PREF_STORE_CLONE_HINT_SNOOZE_AT, 0L),
                System.currentTimeMillis(),
            )
        ) return false
        Thread {
            val (store, action) = findCloneableStore(main, work) ?: return@Thread
            window.decorView.post {
                if (isFinishing) return@post
                // Фиксируем показ сразу: повторный bind после поворота экрана
                // не должен вызывать диалог повторно. "Позже" snooze на неделю.
                local.setInt(LocalStorageManager.PREF_STORE_CLONE_HINT_STATE, StoreCloneHint.STATE_SNOOZED)
                local.setLong(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_SNOOZE_AT,
                    System.currentTimeMillis(),
                )
                showStoreCloneDialog(store, action)
            }
        }.start()
        return true
    }

    /**
     * Кого и что предложить: см. [StoreCloneHint.pickAction]. Доступность в
     * рабочем профиле считаем по факту (FLAG_INSTALLED + launcher-активти),
     * а не по вхождению в showAll-выдачу: в неё попадают системные пакеты из
     * образа прошивки с неснятым FLAG_INSTALLED, из-за чего подсказка на
     * GMS-устройствах не срабатывала никогда (C1).
     */
    private fun findCloneableStore(
        main: IGatekeeperService,
        work: IGatekeeperService,
    ): Pair<ApplicationInfoWrapper, Int>? {
        val mainApps = fetchApps(main)
        val workApps = fetchApps(work)
        return if (mainApps == null || workApps == null) {
            null
        } else {
            pickCloneableStore(mainApps, workApps)
        }
    }

    private fun pickCloneableStore(
        mainApps: List<ApplicationInfoWrapper>,
        workApps: List<ApplicationInfoWrapper>,
    ): Pair<ApplicationInfoWrapper, Int>? {
        val mainHasApk = mainApps.associate { wrapper ->
            wrapper.getPackageName() to (wrapper.isInstalled() && !wrapper.getSourceDir().isNullOrBlank())
        }
        val workStates = workApps.associate { wrapper ->
            wrapper.getPackageName() to StoreCloneHint.classifyWorkState(
                wrapper.isInstalled(),
                wrapper.isHidden(),
                wrapper.canLaunch(),
            )
        }
        val (pkg, action) = StoreCloneHint.pickAction(mainHasApk, workStates) ?: return null
        return mainApps.firstOrNull { it.getPackageName() == pkg }?.let { store -> store to action }
    }

    private fun fetchApps(service: IGatekeeperService): List<ApplicationInfoWrapper>? {
        val latch = CountDownLatch(1)
        var result: List<ApplicationInfoWrapper>? = null
        try {
            service.getApps(object : IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<ApplicationInfoWrapper>) {
                    result = apps
                    latch.countDown()
                }
            }, true)
        } catch (_: RemoteException) {
            return null
        }
        latch.await(STORE_LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS)
        return result
    }

    private fun showStoreCloneDialog(store: ApplicationInfoWrapper, action: Int) {
        val label = store.getLabel() ?: store.getPackageName()
        val title = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            R.string.store_unfreeze_hint_title
        } else {
            R.string.store_clone_hint_title
        }
        val message = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            getString(R.string.store_unfreeze_hint_message, label)
        } else {
            getString(R.string.store_clone_hint_message, label)
        }
        val positiveLabel = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            getString(R.string.store_unfreeze_hint_unfreeze, label)
        } else {
            getString(R.string.store_clone_hint_clone, label)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ ->
                if (action == StoreCloneHint.ACTION_UNFREEZE) {
                    unfreezeStoreInWork(store)
                } else {
                    cloneStoreIntoWork(store)
                }
            }
            .setNegativeButton(R.string.store_clone_hint_later, null)
            .setNeutralButton(R.string.store_clone_hint_never) { _, _ ->
                storage?.setInt(LocalStorageManager.PREF_STORE_CLONE_HINT_STATE, StoreCloneHint.STATE_NEVER)
            }
            .show()
    }

    /**
     * Магазин в рабочем профиле установлен, но заморожен: предложение не
     * клонировать, а разморозить. Идем биндером рабочего сервиса, а не реле:
     * у UNFREEZE_APP нет кросс-профильного фильтра в enforceWorkProfilePolicies,
     * поэтому системный форвардер его не резолвит и transferIntentToProfile
     * всегда отказывает. Диалог показывается только при живом serviceWork,
     * так что биндер здесь уже есть.
     */
    private fun unfreezeStoreInWork(store: ApplicationInfoWrapper) {
        val work = serviceWork ?: run {
            GatekeeperToast.show(this, getString(R.string.clone_fail_no_connection))
            return
        }
        Thread {
            val unfrozen = runCatching { work.unfreezeApp(store) }.isSuccess
            window.decorView.post {
                if (!unfrozen) {
                    GatekeeperToast.show(this, getString(R.string.clone_fail_no_connection))
                    return@post
                }
                storage?.setInt(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                    StoreCloneHint.STATE_NEVER,
                )
                GatekeeperToast.show(
                    this,
                    getString(R.string.unfreeze_success, store.getLabel()),
                )
                refreshAppLists()
            }
        }.start()
    }

    private fun cloneStoreIntoWork(store: ApplicationInfoWrapper) {
        val work = serviceWork ?: return
        val callback = object : IAppInstallCallback.Stub() {
            override fun callback(result: Int) {
                window.decorView.post {
                    val local = storage ?: return@post
                    if (result == RESULT_OK) {
                        local.setInt(
                            LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                            StoreCloneHint.STATE_NEVER,
                        )
                        GatekeeperToast.show(
                            this@MainActivity,
                            String.format(getString(R.string.clone_success), store.getLabel()),
                        )
                    } else {
                        GatekeeperToast.show(
                            this@MainActivity,
                            getString(R.string.clone_fail_generic, store.getLabel(), result),
                        )
                    }
                }
            }
        }
        try {
            work.installApp(store, callback)
        } catch (_: RemoteException) {
            GatekeeperToast.show(this, getString(R.string.clone_fail_no_connection))
        }
    }

    private fun checkPowerDiagnosticsOnceDaily() {
        val localStorage = storage ?: return
        val now = System.currentTimeMillis()
        val lastPromptAt = localStorage.getLong(
                LocalStorageManager.PREF_POWER_DIAGNOSTICS_LAST_PROMPT_AT,
                0L
            )
        if (lastPromptAt > 0L && now - lastPromptAt < POWER_DIAGNOSTICS_PROMPT_INTERVAL_MS) return
        localStorage.setLong(LocalStorageManager.PREF_POWER_DIAGNOSTICS_LAST_PROMPT_AT, now)

        val work = serviceWork ?: return
        Thread {
            val power = getSystemService(PowerManager::class.java)
            val activity = getSystemService(android.app.ActivityManager::class.java)
            val mainSignals = runCatching {
                PowerDiagnostics.ProfileSignals(
                    ignoringBatteryOptimizations =
                        power?.isIgnoringBatteryOptimizations(packageName) ?: return@runCatching null,
                    backgroundRestricted =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            (activity?.isBackgroundRestricted ?: return@runCatching null),
                )
            }.getOrNull()
            val workSignals = runCatching {
                PowerDiagnostics.ProfileSignals(
                    ignoringBatteryOptimizations = work.isIgnoringBatteryOptimizations(),
                    backgroundRestricted = work.isBackgroundRestricted(),
                )
            }.getOrNull()
            val snapshot = PowerDiagnostics.collect(
                mainSignals = mainSignals,
                workSignals = workSignals,
                powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull(),
                deviceIdleMode = runCatching { power?.isDeviceIdleMode }.getOrNull(),
                workServiceAlive = work.asBinder().isBinderAlive,
            )
            if (snapshot.hasProblem()) {
                window.decorView.post {
                    if (!isFinishing) {
                        Snackbar.make(
                            window.decorView,
                            R.string.power_diagnostics_problem_detected,
                            Snackbar.LENGTH_LONG,
                        ).setAction(R.string.power_diagnostics_open) {
                            openPowerDiagnostics()
                        }.show()
                    }
                }
            }
        }.start()
    }

    private fun openPowerDiagnostics() {
        val work = requireWorkService() ?: return
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_OPEN_POWER_DIAGNOSTICS, true)
            putExtra("extras", Bundle().apply { putBinder("profile_service", work.asBinder()) })
        })
    }

    /**
     * До этой ветки неуспех привязки не обрабатывался вовсе: меню оставалось на экране,
     * список приложений не строился, и пользователь смотрел на пустой экран без объяснения.
     *
     * Повторяется только привязка. Через [tryStartWorkService] повторять нельзя: там на
     * неудачный резолв стирается [LocalStorageManager.PREF_HAS_SETUP], и один и тот же отказ,
     * увиденный дважды подряд, отправил бы пользователя в мастер создания профиля при живом
     * профиле. Фильтры `START_SERVICE` и `TRY_START_SERVICE` регистрируются одним вызовом,
     * так что повторная проверка профиля все равно ничего не добавила бы.
     */
    private fun onWorkServiceBindFailed(reason: Int, cause: Throwable?) {
        android.util.Log.w(
            TAG,
            "work service bind failed: reason=$reason, attempts=$workBindAttempts",
            cause
        )
        if (WorkServiceBindFailure.shouldRetrySilently(workBindAttempts)) {
            workBindAttempts++
            bindWorkService()
            return
        }
        showWorkServiceBindFailed(reason)
    }

    private fun showWorkServiceBindFailed(reason: Int, retryStartupProbe: Boolean = false) {
        if (isFinishing) return
        // Именно STARTED: результат привязки приезжает до onResume, а внутри onResume
        // androidx-состояние еще STARTED -- по RESUMED сообщение не показалось бы никогда.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingBindFailureReason = reason
            retryStartupProbeAfterFailure = retryStartupProbe
            return
        }
        pendingBindFailureReason = 0
        retryStartupProbeAfterFailure = false
        AlertDialog.Builder(this)
            .setTitle(R.string.work_service_bind_failed_title)
            .setMessage(WorkServiceBindFailure.messageOf(reason))
            .setCancelable(false)
            .setPositiveButton(R.string.work_service_bind_retry) { _, _ ->
                if (retryStartupProbe) {
                    workStartAttempts = 0
                    tryStartWorkService()
                } else {
                    workBindAttempts = 0
                    bindWorkService()
                }
            }
            .setNegativeButton(R.string.work_service_bind_close) { _, _ -> finish() }
            .show()
    }

    /** Пункты меню, которым нужен сервис профиля: без него объясняем, а не разыменовываем null. */
    private fun requireWorkService(): IGatekeeperService? {
        val service = serviceWork
        if (service == null) {
            showWorkServiceBindFailed(WorkServiceBindFailure.NO_BINDER)
        }
        return service
    }

    private fun runPendingBatchShortcutAction() {
        when (pendingBatchAction) {
            ACTION_BATCH_FREEZE_ALL -> runBatchFreezeAll()
            ACTION_BATCH_UNFREEZE_ALL -> runBatchUnfreezeAll()
            ACTION_REFRESH_APP_LISTS -> refreshAppLists()
        }
        pendingBatchAction = null
    }

    private fun handleBatchShortcutIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_BATCH_FREEZE_ALL -> {
                intent.action = Intent.ACTION_MAIN
                if (servicesAlive()) {
                    runBatchFreezeAll()
                } else {
                    pendingBatchAction = ACTION_BATCH_FREEZE_ALL
                }
                return true
            }
            ACTION_BATCH_UNFREEZE_ALL -> {
                intent.action = Intent.ACTION_MAIN
                if (servicesAlive()) {
                    runBatchUnfreezeAll()
                } else {
                    pendingBatchAction = ACTION_BATCH_UNFREEZE_ALL
                }
                return true
            }
            ACTION_SHOW_BATCH_TOAST -> {
                val resId = intent.getIntExtra(EXTRA_TOAST_RES_ID, 0)
                if (resId != 0) {
                    GatekeeperToast.show(this, resId)
                }
                return true
            }
            ACTION_REFRESH_APP_LISTS -> {
                intent.action = Intent.ACTION_MAIN
                if (servicesAlive()) {
                    refreshAppLists()
                } else {
                    pendingBatchAction = ACTION_REFRESH_APP_LISTS
                }
                return true
            }
        }
        return false
    }

    private fun runBatchFreezeAll() {
        val batchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL).apply {
            component = ComponentName(this@MainActivity, DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(batchIntent)
        startActivity(batchIntent)
        refreshAppLists()
    }

    private fun runBatchUnfreezeAll() {
        val batchIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_ALL).apply {
            component = ComponentName(this@MainActivity, DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(batchIntent)
        startActivity(batchIntent)
        refreshAppLists()
    }

    private fun runAntiSpyStartupFreezeIfNeeded() {
        val s = storage!!
        if (!s.getBoolean(LocalStorageManager.PREF_ANTI_SPY_BOOT_FREEZE_PENDING, false)) {
            return
        }
        AntiSpyManager.clearStartupFreezePending(s)
        AntiSpyManager.runBatchFreezeAll(this)
        AntiSpyManager.syncVpnWatchEverywhere(this)
    }

    private fun startKiller() {
        val main = serviceMain ?: return
        val work = serviceWork ?: return
        val intent = Intent(this, KillerService::class.java)
        val bundle = Bundle().apply {
            putBinder("main", main.asBinder())
            putBinder("work", work.asBinder())
        }
        intent.putExtra("extra", bundle)
        startService(intent)
    }

    private fun buildView() {
        val pager = findViewById<ViewPager2>(R.id.main_pager)
        val nav = findViewById<BottomNavigationView>(R.id.main_bottom_navigation)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> AppListFragment.newInstance(serviceMain!!, false)
                    .also { mainAppListFragment = it }
                1 -> AppListFragment.newInstance(serviceWork!!, true)
                    .also { workAppListFragment = it }
                else -> throw RuntimeException("How did this happen?")
            }

            override fun getItemCount(): Int = 2
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val menuIds = intArrayOf(
                    R.id.bottom_navigation_main,
                    R.id.bottom_navigation_work
                )
                nav.selectedItemId = menuIds[position]
            }
        })
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.bottom_navigation_main -> pager.currentItem = 0
                R.id.bottom_navigation_work -> pager.currentItem = 1
            }
            true
        }
    }

    fun getOtherService(isRemote: Boolean): IGatekeeperService =
        if (isRemote) serviceMain!! else serviceWork!!

    fun servicesAlive(): Boolean =
        ServiceLiveness.areAlive(serviceMain?.asBinder(), serviceWork?.asBinder())

    private fun registerStartActivityProxies() {
        try {
            serviceMain!!.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    this@MainActivity.startActivity(intent)
                }
            })
            serviceWork!!.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    val dummyIntent = Intent(intent.action)
                    if (!Utility.tryTransferIntentToProfileUnsigned(this@MainActivity, dummyIntent)) {
                        return
                    }
                    intent.component = dummyIntent.component
                    this@MainActivity.startActivity(intent)
                }
            })
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        visibleInstance = this
        AntiSpyManager.syncVpnWatchEverywhere(this)
        if (pendingVpnBlockReason != 0) {
            val reason = pendingVpnBlockReason
            pendingVpnBlockReason = 0
            showAntiSpyVpnLaunchBlockedDialog(reason)
        }
        if (pendingBindFailureReason != 0) {
            val reason = pendingBindFailureReason
            val retryStartupProbe = retryStartupProbeAfterFailure
            pendingBindFailureReason = 0
            retryStartupProbeAfterFailure = false
            showWorkServiceBindFailed(reason, retryStartupProbe)
        }
        if (pendingDocumentsUi) {
            pendingDocumentsUi = false
            window.decorView.removeCallbacks(documentsUiWarmUpTimeout)
            openDocumentsUi()
        }
        if (serviceMain != null && serviceWork != null && !servicesAlive()) {
            doOnDestroy()
            restarting = true
            val intent = intent
            finish()
            startActivity(intent)
            return
        }
        startWorkListPolling()
    }

    override fun onStop() {
        // Реле и его activity прозрачные, onStop при прогреве не приходит. Если он пришел,
        // пользователь ушел из приложения -- проводник по возвращении открывать нельзя.
        pendingDocumentsUi = false
        window.decorView.removeCallbacks(documentsUiWarmUpTimeout)
        super.onStop()
    }

    override fun onPause() {
        isResumed = false
        stopWorkListPolling()
        if (visibleInstance === this) {
            visibleInstance = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopWorkListPolling()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(antiSpyVpnBlockReceiver)
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(appListRefreshReceiver)
        super.onDestroy()
        if (!restarting) {
            doOnDestroy()
        }
    }

    private fun doOnDestroy() {
        stopService(Intent(this, KillerService::class.java))
        try {
            serviceWork?.stopGatekeeperService(true)
        } catch (_: Exception) {
        }
        try {
            serviceMain?.stopGatekeeperService(false)
        } catch (_: Exception) {
        }
        AntiSpyManager.syncVpnWatchEverywhere(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)

        val searchView = menu.findItem(R.id.main_menu_search).actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String): Boolean {
                val intent = Intent(BROADCAST_SEARCH_FILTER_CHANGED).apply {
                    putExtra("text", newText.lowercase().trim())
                }
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)
                return true
            }
        })
        return true
    }

    fun runAfterVpnGateCleared(packageName: String, forceGate: Boolean, action: Runnable) {
        AntiSpyLaunchGate.runBeforeAutoFreezeAccess(
            this,
            LocalStorageManager.getInstance(),
            packageName,
            forceGate,
            action,
            AntiSpyLaunchGate.BlockedCallback { reason ->
                pendingVpnBlockReason = reason
                showAntiSpyVpnLaunchBlockedDialog(reason)
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    requestAntiSpyVpnPermission()
                }
            }
        )
    }

    /** Anti Spy: clear third-party VPN before opening the APK file picker for work-profile install. */
    private fun runInstallApkAfterVpnGateCleared() {
        pendingApkInstallAfterVpnGate = true
        AntiSpyLaunchGate.runBeforeAutoFreezeAccess(
            this,
            LocalStorageManager.getInstance(),
            "",
            forceGate = true,
            Runnable {
                pendingApkInstallAfterVpnGate = false
                selectApk.launch(null)
            },
            AntiSpyLaunchGate.BlockedCallback { reason ->
                pendingVpnBlockReason = reason
                showAntiSpyVpnLaunchBlockedDialog(reason)
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    requestAntiSpyVpnPermission()
                } else {
                    pendingApkInstallAfterVpnGate = false
                }
            }
        )
    }

    private fun requestAntiSpyNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        postNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestAntiSpyVpnPermission() {
        if (isFinishing) return
        // Именно STARTED, а не RESUMED: вызов из onResume, где androidx-состояние еще
        // STARTED (ReportFragment шлет ON_RESUME после onActivityPostResumed). Гейт RESUMED
        // откладывал бы запрос до следующего возврата на экран, то есть навсегда.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingVpnBlockReason = AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            retryPendingLaunchAfterVpnPermission()
            return
        }
        antiSpyVpnPermission.launch(prepare)
    }

    private fun retryPendingLaunchAfterVpnPermission() {
        if (pendingApkInstallAfterVpnGate) {
            runInstallApkAfterVpnGateCleared()
            return
        }
        if (TextUtils.isEmpty(pendingLaunchPackageName)) return
        val launchIntent = Intent(DummyActivity.UNFREEZE_AND_LAUNCH).apply {
            component = ComponentName(this@MainActivity, DummyActivity::class.java)
            putExtra("packageName", pendingLaunchPackageName)
        }
        DummyActivity.registerSameProcessRequest(launchIntent)
        startActivity(launchIntent)
    }

    private fun showAntiSpyVpnLaunchBlockedDialog(reason: Int) {
        if (isFinishing) return
        // STARTED по той же причине, что и в showWorkServiceBindFailed: диалог, отложенный
        // из onResume по гейту RESUMED, не показался бы никогда (план Фазы 13, п.15).
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingVpnBlockReason = reason
            return
        }
        val message = when {
            reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED ->
                getString(R.string.anti_spy_vpn_permission_required)
            pendingApkInstallAfterVpnGate ->
                getString(R.string.anti_spy_vpn_block_install_apk)
            else ->
                getString(R.string.anti_spy_vpn_block_launch_manual)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.anti_spy_vpn_block_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
        if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
            builder.setNeutralButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                requestAntiSpyVpnPermission()
            }
        }
        builder.show()
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(BROADCAST_CONTEXT_MENU_CLOSED))
    }

    private fun createBatchShortcut(isFreeze: Boolean) {
        val launchIntent = batchShortcutIntent(
            if (isFreeze) DummyActivity.PUBLIC_FREEZE_ALL else DummyActivity.PUBLIC_UNFREEZE_ALL
        )
        Utility.createLauncherShortcut(
            this,
            launchIntent,
            Utility.createBatchShortcutIcon(
                this,
                if (isFreeze) R.drawable.ic_shortcut_freeze else R.drawable.ic_shortcut_unfreeze,
            ),
            if (isFreeze) "gatekeeper-freeze-all" else "gatekeeper-unfreeze-all",
            getString(if (isFreeze) R.string.freeze_all_shortcut else R.string.unfreeze_all_shortcut),
        )
    }

    private fun toggleShowAll(item: MenuItem) {
        val update = Runnable {
            showAll = !item.isChecked
            item.isChecked = showAll
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(AppListFragment.BROADCAST_REFRESH))
        }
        if (!item.isChecked) {
            AlertDialog.Builder(this)
                .setMessage(R.string.show_all_warning)
                .setPositiveButton(R.string.first_run_alert_continue) { _, _ -> update.run() }
                .setNegativeButton(R.string.first_run_alert_cancel, null)
                .show()
        } else {
            update.run()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_menu_unfreeze_all -> {
                runBatchUnfreezeAll()
                true
            }
            R.id.main_menu_freeze_all -> {
                runBatchFreezeAll()
                true
            }
            R.id.main_menu_settings -> {
                openSettings()
                true
            }
            R.id.main_menu_create_freeze_all_shortcut,
            R.id.main_menu_create_unfreeze_all_shortcut -> {
                createBatchShortcut(item.itemId == R.id.main_menu_create_freeze_all_shortcut)
                true
            }
            R.id.main_menu_install_app_to_profile -> {
                if (requireWorkService() != null) {
                    runInstallApkAfterVpnGateCleared()
                }
                true
            }
            R.id.main_menu_show_all -> {
                toggleShowAll(item)
                true
            }
            R.id.main_menu_file_shuttle -> {
                openFileShuttleEntry()
                true
            }
            R.id.main_menu_export_backup -> {
                createBackup.launch("gatekeeper-backup.json")
                true
            }
            R.id.main_menu_import_backup -> {
                openBackup.launch(arrayOf("*/*"))
                true
            }
            R.id.main_menu_documents_ui -> {
                openDocumentsUiAfterWarmUp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() {
        requireWorkService()?.let { work ->
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("extras", Bundle().apply { putBinder("profile_service", work.asBinder()) })
            })
        }
    }

    /**
     * Прогрев связи с файловым шаттлом перед проводником. Открывать DocumentsUI сразу после
     * [FileShuttleConnection.requestBind] нельзя: запуск сносит с экрана кросс-профильное реле,
     * и привязка не доезжает (замер Фазы 3). Поэтому проводник открывается после возврата реле,
     * из [onResume]; запасной путь по таймауту нужен там, где реле не поднималось вовсе,
     * иначе пункт меню молча ничего не сделает.
     */
    private fun openDocumentsUiAfterWarmUp() {
        val warmingUp = SettingsManager.getInstance().getCrossProfileFileChooserEnabled() &&
            FileShuttleConnection.peek() == null &&
            FileShuttleConnection.requestBind(this)
        if (!warmingUp) {
            openDocumentsUi()
            return
        }
        pendingDocumentsUi = true
        window.decorView.postDelayed(documentsUiWarmUpTimeout, DOCUMENTS_UI_WARMUP_MS)
    }

    private val documentsUiWarmUpTimeout = Runnable {
        if (pendingDocumentsUi && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingDocumentsUi = false
            openDocumentsUi()
        }
    }

    private fun openDocumentsUi() {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(null, "vnd.android.document/root")
        })
    }

    /**
     * C3: вход в шаттл с верхней панели, а не из глубины меню. Если
     * переключатель «Перенос файлов» выключен, старый путь молча открывал
     * пустой системный проводник -- отсюда репорты «ничего не работает»
     * (4PDA #2124-#2142). Теперь объясняем и включаем одним тапом.
     */
    private fun openFileShuttleEntry() {
        if (SettingsManager.getInstance().getCrossProfileFileChooserEnabled()) {
            openDocumentsUiAfterWarmUp()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.file_shuttle_enable_title)
            .setMessage(R.string.file_shuttle_enable_message)
            .setPositiveButton(R.string.file_shuttle_enable_action) { _, _ ->
                SettingsManager.getInstance().setCrossProfileFileChooserEnabled(true)
                openDocumentsUiAfterWarmUp()
            }
            .setNegativeButton(R.string.first_run_alert_cancel, null)
            .show()
    }

    /**
     * C4: бэкап настроек и списков приложений (4PDA #947, #948, #957).
     * Данные приложений без root недоступны — экспортируем честные границы:
     * настройки из [BackupPayload.EXPORTABLE_SETTINGS] (без ключа
     * авторизации), пакеты обоих профилей и список автозаморозки. Файл --
     * JSON через SAF, никаких прав на хранилище.
     */
    private fun onBackupLocationPicked(uri: Uri?) {
        if (uri == null) return
        val main = serviceMain
        val work = serviceWork
        Thread {
            val local = storage ?: return@Thread
            val payload = BackupPayload.Payload(
                settings = local.snapshotSettings(BackupPayload.EXPORTABLE_SETTINGS),
                mainApps = main?.let { fetchApps(it) }?.map { it.getPackageName() } ?: emptyList(),
                workApps = work?.let { fetchApps(it) }?.map { it.getPackageName() } ?: emptyList(),
                autoFreezeWork = local.getStringList(
                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE
                ).toList(),
            )
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(BackupPayload.serialize(payload).toByteArray())
                } != null
            }.getOrDefault(false)
            window.decorView.post {
                GatekeeperToast.show(
                    this,
                    getString(if (ok) R.string.backup_export_success else R.string.backup_export_failed),
                )
            }
        }.start()
    }

    private fun onBackupFilePicked(uri: Uri?) {
        if (uri == null) return
        Thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val payload = text?.let { BackupPayload.parse(it) }
            window.decorView.post {
                val local = storage ?: return@post
                if (payload == null) {
                    GatekeeperToast.show(this, R.string.backup_import_invalid)
                    return@post
                }
                local.applySettings(payload.settings)
                // Компоненты (провайдер файлов, платёжный стаб) и сторож
                // VPN подхватывают значения из префов; правила ссылок C2
                // применяем сразу, не дожидаясь тумблера в настройках.
                SettingsManager.getInstance().applyAll()
                applyImportedLinkRules()
                GatekeeperToast.show(
                    this,
                    getString(
                        R.string.backup_import_success,
                        payload.settings.size,
                        payload.mainApps.size,
                        payload.workApps.size,
                    ),
                )
                maybeOfferAppRestore(payload)
            }
        }.start()
    }

    /**
     * C2: правила ссылок из бэкапа лежат в префах после applySettings, но в
     * профиле ещё не применены — догоняем сервис рабочего профиля. Отказ
     * не критичен: список в префах, применится при следующем enforce.
     */
    private fun applyImportedLinkRules() {
        val work = serviceWork ?: return
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
            .toList()
        Thread {
            runCatching { work.setCrossProfileLinkRules(rules) }
        }.start()
    }

    /**
     * C4+: после импорта предлагаем доклонировать недостающие приложения
     * рабочего профиля из списка бэкапа. Донор -- личный профиль; кандидаты
     * считаются чистой функцией [BackupPayload.restorableWorkApps].
     */
    private fun maybeOfferAppRestore(payload: BackupPayload.Payload) {
        val main = serviceMain
        val work = serviceWork
        if (main == null || work == null) return
        Thread {
            val mainApps = fetchApps(main)
            val workApps = fetchApps(work)
            val restorable = if (mainApps == null || workApps == null) {
                emptyList()
            } else {
                val workInstalled = workApps.map { it.getPackageName() }.toSet()
                val mainInstalled = mainApps.map { it.getPackageName() }.toSet()
                BackupPayload.restorableWorkApps(payload.workApps, workInstalled, mainInstalled)
            }
            if (restorable.isEmpty()) return@Thread
            val byPkg = mainApps?.associateBy { it.getPackageName() } ?: emptyMap()
            val candidates = restorable.mapNotNull { byPkg[it] }
            if (candidates.isEmpty()) return@Thread
            window.decorView.post {
                if (isFinishing) return@post
                AlertDialog.Builder(this)
                    .setTitle(R.string.backup_restore_title)
                    .setMessage(getString(R.string.backup_restore_message, candidates.size))
                    .setPositiveButton(R.string.backup_restore_now) { _, _ ->
                        restoreWorkAppsSequentially(candidates, work)
                    }
                    .setNegativeButton(R.string.backup_restore_later, null)
                    .show()
            }
        }.start()
    }

    /**
     * Ставим восстанавливаемые приложения строго по одному: параллельные
     * PackageInstaller-сессии кладут резолвер. Каждый успех возвращается
     * в список автозаморозки, как при ручном клонировании.
     */
    private fun restoreWorkAppsSequentially(apps: List<ApplicationInfoWrapper>, work: IGatekeeperService) {
        var index = 0
        var failed = 0
        fun installNext() {
            if (index >= apps.size) {
                window.decorView.post {
                    GatekeeperToast.show(
                        this,
                        getString(R.string.backup_restore_done, apps.size - failed, apps.size, failed),
                    )
                }
                return
            }
            val app = apps[index]
            index++
            val callback = object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    if (result == RESULT_OK) {
                        AutoFreezeDefaults.enableForWorkProfile(
                            this@MainActivity,
                            app.getPackageName(),
                            clearOptOut = true,
                        )
                    } else {
                        failed++
                    }
                    installNext()
                }
            }
            try {
                work.installApp(app, callback)
            } catch (_: RemoteException) {
                failed++
                installNext()
            }
        }
        installNext()
    }

    /**
     * Разовый Snackbar, указывающий на кнопку переноса файлов. Показываем
     * только когда шаттл включён и диалог про магазин в этот раз не вылез,
     * чтобы не наслаивать подсказки друг на друга.
     */
    private fun maybeShowFileShuttleHint() {
        val local = storage
        val alreadyShown = local?.getBooleanFresh(
            LocalStorageManager.PREF_FILE_SHUTTLE_HINT_SHOWN,
            false,
        ) ?: true
        val shuttleEnabled = SettingsManager.getInstance().getCrossProfileFileChooserEnabled()
        if (local == null || alreadyShown || !shuttleEnabled) return
        local.setBoolean(LocalStorageManager.PREF_FILE_SHUTTLE_HINT_SHOWN, true)
        window.decorView.postDelayed({
            if (isFinishing) return@postDelayed
            Snackbar.make(window.decorView, R.string.file_shuttle_hint, Snackbar.LENGTH_LONG)
                .setAction(R.string.file_shuttle_hint_action) { openFileShuttleEntry() }
                .show()
        }, FILE_SHUTTLE_HINT_DELAY_MS)
    }

    private val workListPollRunnable = Runnable { pollWorkAppListChanges() }

    /** Detect RuStore / work-profile installs while the user stays on the work tab. */
    private fun startWorkListPolling() {
        workListPollHandler.removeCallbacks(workListPollRunnable)
        if (!isResumed || !servicesAlive()) {
            return
        }
        workListPollHandler.post(workListPollRunnable)
    }

    private fun stopWorkListPolling() {
        workListPollHandler.removeCallbacks(workListPollRunnable)
        workPackageSnapshot = null
    }

    private fun pollWorkAppListChanges() {
        if (!isResumed) {
            stopWorkListPolling()
            return
        }
        val work = serviceWork
        if (work == null || !servicesAlive()) {
            workListPollHandler.postDelayed(workListPollRunnable, WORK_LIST_POLL_INTERVAL_MS)
            return
        }
        try {
            work.getApps(object : IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<ApplicationInfoWrapper>) {
                    if (!isResumed) {
                        return
                    }
                    val current = apps.map { it.getPackageName() }.toSet()
                    val previous = workPackageSnapshot
                    if (previous != null && previous != current) {
                        android.util.Log.i(
                            "MainActivity",
                            "work profile app set changed (${previous.size} -> ${current.size}), refreshing",
                        )
                        runOnUiThread { refreshAppLists() }
                    }
                    workPackageSnapshot = current
                    if (isResumed) {
                        workListPollHandler.postDelayed(
                            workListPollRunnable,
                            WORK_LIST_POLL_INTERVAL_MS,
                        )
                    }
                }
            }, showAll)
        } catch (_: RemoteException) {
            if (isResumed) {
                workListPollHandler.postDelayed(workListPollRunnable, WORK_LIST_POLL_INTERVAL_MS)
            }
        }
    }

    fun refreshAppLists() {
        val fragments = LinkedHashSet<AppListFragment>()
        mainAppListFragment?.let { fragments.add(it) }
        workAppListFragment?.let { fragments.add(it) }
        for (tag in APP_LIST_FRAGMENT_TAGS) {
            (supportFragmentManager.findFragmentByTag(tag) as? AppListFragment)?.let { fragments.add(it) }
        }
        for (fragment in fragments) {
            fragment.refresh()
        }
    }

    fun scheduleAppListRefresh(followUpAfterInstall: Boolean = false) {
        refreshAppLists()
        Utility.scheduleAppListRefresh(this)
        if (followUpAfterInstall) {
            window.decorView.postDelayed({ refreshAppLists() }, APP_LIST_INSTALL_REFRESH_MS)
        }
    }

    private fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val work = requireWorkService() ?: return
        val proxy = UriForwardProxy(applicationContext, uri)
        try {
            work.installApk(proxy, object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    runOnUiThread {
                        if (result == RESULT_OK) {
                            GatekeeperToast.show(
                                this@MainActivity,
                                R.string.install_app_to_profile_success,
                                android.widget.Toast.LENGTH_LONG,
                            )
                            scheduleAppListRefresh(followUpAfterInstall = true)
                        }
                    }
                }
            })
        } catch (_: RemoteException) {
        }
    }

    private fun batchShortcutIntent(action: String): Intent =
        Intent(this, DummyActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        private const val TAG = "MainActivity"
        private const val STORE_LOOKUP_TIMEOUT_SEC = 5L
        private const val FILE_SHUTTLE_HINT_DELAY_MS = 1500L

        @JvmField
        @Volatile
        var isResumed = false

        @Volatile
        private var visibleInstance: MainActivity? = null

        /** Refresh app lists when the personal-profile UI is on screen. */
        @JvmStatic
        fun refreshIfVisible() {
            val activity = visibleInstance ?: return
            if (!isResumed) {
                return
            }
            activity.runOnUiThread { activity.refreshAppLists() }
        }

        const val ACTION_BATCH_FREEZE_ALL = ProfileActions.BATCH_FREEZE_ALL
        const val ACTION_BATCH_UNFREEZE_ALL = ProfileActions.BATCH_UNFREEZE_ALL
        const val ACTION_SHOW_BATCH_TOAST = ProfileActions.SHOW_BATCH_TOAST
        const val ACTION_REFRESH_APP_LISTS = ProfileActions.REFRESH_APP_LISTS
        const val EXTRA_TOAST_RES_ID = "toast_res_id"
        const val BROADCAST_CONTEXT_MENU_CLOSED =
            "io.gatekeeper.broadcast.CONTEXT_MENU_CLOSED"
        const val BROADCAST_SEARCH_FILTER_CHANGED =
            "io.gatekeeper.broadcast.SEARCH_FILTER_CHANGED"
        private val APP_LIST_FRAGMENT_TAGS = arrayOf("f0", "f1")
        private const val APP_LIST_INSTALL_REFRESH_MS = 2000L
        private const val WORK_LIST_POLL_INTERVAL_MS = 2000L
        private const val POWER_DIAGNOSTICS_PROMPT_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Сквозной путь привязки шаттла -- ~300 мс (замер Фазы 1); ждем с запасом. */
        private const val DOCUMENTS_UI_WARMUP_MS = 1500L
    }
}
