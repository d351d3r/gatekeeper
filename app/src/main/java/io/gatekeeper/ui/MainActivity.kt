@file:Suppress("DEPRECATION") // legacy version-gated paths (LocalBroadcastManager)

package io.gatekeeper.ui

import android.Manifest
import android.widget.TextView
import android.view.View
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
import android.annotation.SuppressLint
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.gatekeeper.BuildConfig
import io.gatekeeper.R
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.services.IGetAppsCallback
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.services.IStartActivityProxy
import io.gatekeeper.services.KillerService
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.WorkProfileStatus
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.FileShuttleConnection
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.PairingCode
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.PowerDiagnostics
import io.gatekeeper.util.ServiceLiveness
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.UriForwardProxy
import io.gatekeeper.util.ProfileNotifier
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkServiceBindFailure
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
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
    private val tryStartWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::tryStartWorkServiceCb)
    private val bindWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::bindWorkServiceCb)

    // Выбор пресета -- for-result, чтобы продолжить онбординг тем же заходом: иначе
    // экран «Доступы» ждал бы следующего запуска (bindWorkServiceCb по возврату не
    // перевызывается), и «сначала пресет, потом разрешения» рвалось на два сеанса.
    private val isolationSetup =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            continueOnboardingAfterIsolation()
        }
    private val requestRepair =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                workBindAttempts = 0
                bindWorkService()
            } else {
                GatekeeperToast.show(this, R.string.repair_declined)
            }
        }
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
    private val storeCloneFlow by lazy { StoreCloneFlow(this) }
    private val workListPoller by lazy { WorkListPoller(this) }
    private val batchShortcutFlow by lazy { BatchShortcutFlow(this) }
    /** Доступ к биндеру рабочего сервиса для выделенных флоу (StoreCloneFlow). */
    fun workServiceOrNull(): IGatekeeperService? = serviceWork
    var showAll = false
    private var pendingVpnBlockReason = 0
    private var pendingLaunchPackageName: String? = null
    private var pendingApkInstallAfterVpnGate = false
    private var workStartAttempts = 0
    private var workBindAttempts = 0
    private var pendingBindFailureReason = 0
    private var retryStartupProbeAfterFailure = false
    private var pendingDocumentsUi = false
    private var mainAppListFragment: AppListFragment? = null
    private var workAppListFragment: AppListFragment? = null

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
        batchShortcutFlow.dispatchBackground(intent!!)
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
            batchShortcutFlow.handleShortcutIntent(intent)
            AntiSpyManager.onApplicationLaunch(s, BuildConfig.VERSION_CODE)
            requestAntiSpyNotificationPermissionIfNeeded()
            SettingsManager.getInstance().applyAll()
            bindServices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        batchShortcutFlow.handleShortcutIntent(intent)
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
            batchShortcutFlow.runPending()
            workListPoller.start()
        }
        buildView()
        // Первый запуск ведет за руку: сначала пресет изоляции, потом разрешения,
        // и только потом обычные подсказки. Друг на друга они не наслаиваются.
        val handled = askIsolationPresetIfNeeded() || showAccessesOnce()
        if (!handled && !storeCloneFlow.maybePrompt(serviceMain, serviceWork)) {
            maybeShowFileShuttleHint()
        }
    }

    /**
     * Вопрос про пресет задает мастер, но профиль может появиться и мимо него:
     * подхват живого профиля, возврат владения, оборванный мастер. Спрашиваем один
     * раз за жизнь установки, иначе выбор молча остается за нас.
     *
     * @return true, если диалог показан -- другие подсказки в этот раз не всплывают.
     */
    private fun askIsolationPresetIfNeeded(): Boolean {
        val asked = storage?.getBoolean(LocalStorageManager.PREF_ISOLATION_PRESET_CHOSEN) ?: true
        if (asked) return false
        isolationSetup.launch(Intent(this, IsolationSetupActivity::class.java))
        return true
    }

    /** Продолжение онбординга после выбора пресета: разрешения, затем обычные подсказки. */
    private fun continueOnboardingAfterIsolation() {
        if (!showAccessesOnce() && !storeCloneFlow.maybePrompt(serviceMain, serviceWork)) {
            maybeShowFileShuttleHint()
        }
    }

    /**
     * Разрешения показываются сразу после настройки, один раз. Без этого человек
     * пользуется приложением и по дороге выясняет, что часть функций молча не
     * работает, а где их включить -- надо искать. Экран называет каждое
     * разрешение, зачем оно и что без него мертво.
     *
     * @return true, если экран открыт.
     */
    private fun showAccessesOnce(): Boolean {
        val shown = storage?.getBoolean(LocalStorageManager.PREF_ACCESSES_SHOWN) ?: true
        if (shown) return false
        storage?.setBoolean(LocalStorageManager.PREF_ACCESSES_SHOWN, true)
        openSettingsScreen(SCREEN_ACCESSES)
        return true
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
            putExtra(SettingsActivity.EXTRA_OPEN_SCREEN, "settings_root_diagnostics")
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
        val dialog = AlertDialog.Builder(this)
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
        // Профиль жив, а реле молчит -- вероятнее всего разошлись ключи после
        // переустановки личной копии. Даем выход, который не стоит профиля.
        if (hasManagedProfile()) {
            dialog.setNeutralButton(R.string.work_service_bind_repair) { _, _ -> offerProfileRepair() }
        }
        dialog.show()
    }

    /**
     * Перепривязка личной копии к живому профилю. Код показывается здесь, до
     * отправки: в рабочем профиле человек сверит его с тем, что покажет диалог
     * подтверждения, и по несовпадению узнает чужой запрос (ProfileRepairFlow).
     */
    private fun offerProfileRepair() {
        val key = AuthenticationUtility.currentKey()
        AlertDialog.Builder(this)
            .setTitle(R.string.repair_request_title)
            .setMessage(getString(R.string.repair_request_message, PairingCode.of(key)))
            .setPositiveButton(R.string.repair_request_continue) { _, _ -> sendRepairRequest(key) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendRepairRequest(key: String) {
        val intent = Intent(DummyActivity.REQUEST_REPAIR).apply {
            putExtra(ProfileRepairFlow.EXTRA_AUTH_KEY, key)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            Utility.transferIntentToProfile(this, intent)
            requestRepair.launch(intent)
        } catch (e: IllegalStateException) {
            onWorkServiceBindFailed(WorkServiceBindFailure.NO_RESOLUTION, e)
        } catch (e: ActivityNotFoundException) {
            onWorkServiceBindFailed(WorkServiceBindFailure.NO_RESOLUTION, e)
        }
    }

    /** Пункты меню, которым нужен сервис профиля: без него объясняем, а не разыменовываем null. */
    private fun requireWorkService(): IGatekeeperService? {
        val service = serviceWork
        if (service == null) {
            showWorkServiceBindFailed(WorkServiceBindFailure.NO_BINDER)
        }
        return service
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

    /**
     * Счётчики профиля для карточки состояния. Держим их в активности: фрагмент
     * пересоздаётся вместе со страницей пейджера, а карточка живёт снаружи.
     */
    private var workAppsTotal = 0
    private var workAppsFrozen = 0

    /** Вызывается фрагментом рабочего профиля после каждой загрузки списка. */
    fun onWorkAppsLoaded(total: Int, frozen: Int) {
        workAppsTotal = total
        workAppsFrozen = frozen
        updateStatusCard()
        // Плитка и виджет спросить рабочий профиль сами не могут: кладем им факт.
        WorkProfileStatus.store(this, total, frozen)
    }

    private fun updateStatusCard() {
        val card = findViewById<View>(R.id.main_status_card) ?: return
        val onProfile = findViewById<ViewPager2>(R.id.main_pager)?.currentItem == PAGE_WORK
        card.visibility = if (onProfile && workAppsTotal > 0) View.VISIBLE else View.GONE
        if (card.visibility == View.VISIBLE) {
            fillStatusCard()
        }
    }

    /**
     * Кнопки массовой заморозки ходят через PUBLIC_FREEZE_ALL / PUBLIC_UNFREEZE_ALL,
     * а те берут список автозаморозки, а не весь профиль. Пустой список -- это не
     * две серые кнопки (читались как поломка), а одна «Настроить автозаморозку»,
     * ведущая туда, где список наполняется.
     */
    private fun fillStatusCard() {
        val listSize = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE).size
        val buttons = findViewById<View>(R.id.main_status_buttons)
        val setup = findViewById<MaterialButton>(R.id.main_status_setup)
        val counts = findViewById<TextView>(R.id.main_status_counts)
        val hasList = listSize > 0
        buttons.isVisible = hasList
        setup.isVisible = !hasList
        if (hasList) {
            findViewById<MaterialButton>(R.id.main_status_freeze).text =
                getString(R.string.action_freeze_list, listSize)
            findViewById<MaterialButton>(R.id.main_status_unfreeze).text =
                getString(R.string.action_unfreeze_list, listSize)
            counts.text = getString(
                R.string.status_counts,
                workAppsTotal,
                workAppsFrozen,
                workAppsTotal - workAppsFrozen,
            )
        } else {
            counts.text =
                getString(R.string.status_counts_no_list, workAppsTotal, workAppsFrozen)
        }
    }

    private fun buildView() {
        val pager = findViewById<ViewPager2>(R.id.main_pager)
        findViewById<MaterialButton>(R.id.main_status_freeze).setOnClickListener {
            batchShortcutFlow.freezeAll()
        }
        findViewById<MaterialButton>(R.id.main_status_unfreeze).setOnClickListener {
            batchShortcutFlow.unfreezeAll()
        }
        findViewById<MaterialButton>(R.id.main_status_setup).setOnClickListener {
            openSettingsScreen(SCREEN_FREEZE)
        }

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment = when (position) {
                PAGE_WORK -> AppListFragment.newInstance(serviceWork!!, true)
                    .also { workAppListFragment = it }
                else -> AppListFragment.newInstance(serviceMain!!, false)
                    .also { mainAppListFragment = it }
            }

            override fun getItemCount(): Int = 2
        }
        // Личный список -- не вкладка, а разовый выбор: свайпом туда не попасть,
        // только кнопкой, и назад он закрывается как отдельный экран.
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                showPickerChrome(position == PAGE_PICK)
                updateStatusCard()
            }
        })
        findViewById<ExtendedFloatingActionButton>(R.id.main_add_app).setOnClickListener {
            pager.currentItem = PAGE_PICK
        }
        onBackPressedDispatcher.addCallback(this, closePicker)
    }

    /** Возврат из выбора приложения ведет обратно в профиль, а не из приложения. */
    private val closePicker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            findViewById<ViewPager2>(R.id.main_pager).currentItem = PAGE_WORK
        }
    }

    /** Выбор приложения выглядит отдельным экраном: свой заголовок и стрелка назад. */
    private fun showPickerChrome(picking: Boolean) {
        closePicker.isEnabled = picking
        findViewById<ExtendedFloatingActionButton>(R.id.main_add_app).isVisible = !picking
        findViewById<View>(R.id.main_status_card).isVisible = !picking
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(picking)
            title = getString(if (picking) R.string.main_pick_app_title else R.string.app_name)
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
            // Перезапуск явным интентом, а не принятым: экран экспортирован, и чужой
            // интент без компонента система при повторной отправке разрешила бы куда
            // угодно. Из принятого нужен только action -- больше отсюда ничего не
            // читается (extras разбирает ресивер, а не activity).
            val restart = Intent(this, MainActivity::class.java).setAction(intent.action)
            finish()
            startActivity(restart)
            return
        }
        workListPoller.start()
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
        workListPoller.stop()
        if (visibleInstance === this) {
            visibleInstance = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        workListPoller.stop()
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

    @SuppressLint("RestrictedApi") // единственный способ показать иконки в переполнении
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)
        // Меню под тремя точками по умолчанию рисуется без иконок: пункты в нем
        // приходится читать целиком, вместо того чтобы узнавать по значку.
        (menu as? androidx.appcompat.view.menu.MenuBuilder)?.setOptionalIconsVisible(true)

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
            R.id.main_menu_settings -> {
                openSettings()
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
            R.id.main_menu_documents_ui -> {
                // Один названный пункт вместо неподписанной иконки на панели и
                // второго пункта рядом: оба открывали один и тот же выбор файлов.
                openFileShuttleEntry()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() = openSettingsScreen(null)

    /** [screenKey] -- ключ строки корня настроек, которую открыть сразу; null -- корень. */
    private fun openSettingsScreen(screenKey: String?) {
        val work = requireWorkService() ?: return
        val main = serviceMain ?: return
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra(
                "extras",
                Bundle().apply {
                    putBinder("profile_service", work.asBinder())
                    putBinder("main_service", main.asBinder())
                },
            )
            if (screenKey != null) putExtra(SettingsActivity.EXTRA_OPEN_SCREEN, screenKey)
        })
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
        // Кнопка на панели открывает перенос, но не заводит его: включение живет
        // там же, где остальные настройки связи профилей, и подчиняется пресету.
        AlertDialog.Builder(this)
            .setTitle(R.string.file_shuttle_enable_title)
            .setMessage(R.string.file_shuttle_disabled_message)
            .setPositiveButton(R.string.file_shuttle_open_settings) { _, _ ->
                openSettingsScreen("settings_root_files")
            }
            .setNegativeButton(R.string.first_run_alert_cancel, null)
            .show()
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
        ProfileNotifier.scheduleAppListRefresh(this)
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

    companion object {
        /** Профиль -- главный экран; личный список открывается поверх него. */
        private const val PAGE_WORK = 0
        private const val PAGE_PICK = 1

        /** Ключ строки корня настроек с разрешениями. */
        private const val SCREEN_ACCESSES = "settings_root_accesses"

        /** Ключ строки корня настроек с заморозкой. */
        private const val SCREEN_FREEZE = "settings_root_freeze"

        private const val TAG = "MainActivity"
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
        private const val POWER_DIAGNOSTICS_PROMPT_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Сквозной путь привязки шаттла -- ~300 мс (замер Фазы 1); ждем с запасом. */
        private const val DOCUMENTS_UI_WARMUP_MS = 1500L
    }
}
