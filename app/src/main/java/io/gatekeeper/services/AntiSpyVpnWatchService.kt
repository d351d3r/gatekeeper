package io.gatekeeper.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.gatekeeper.R
import io.gatekeeper.util.AntiSpyDummyVpnDisconnector
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.AntiSpyReaction
import io.gatekeeper.util.AntiSpyTrigger
import io.gatekeeper.util.AntiSpyVpnPromptManager
import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.AntiSpyWatchRestart
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.VpnTunnelDetector
import io.gatekeeper.util.WorkProfileBatchFreeze
import io.gatekeeper.util.ZindanToast

/**
 * Сторож VPN и блокировки экрана. Ничего не делает, пока пользователь не включил функцию:
 * заморозка гасит уведомления рабочего профиля, поэтому решение принимает только он
 * (`docs/antispy_settings_requirements.md`).
 */
class AntiSpyVpnWatchService : Service() {
    private var connectivityManager: ConnectivityManager? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val freezeRunnable = Runnable { runPendingFreeze() }
    private val pollRunnable = Runnable { pollVpnState() }
    private var vpnPresent = false
    private var foregroundStarted = false
    private var foregroundSinceMs = 0L
    private var connectivityReceiver: BroadcastReceiver? = null
    private var freezeCompleteReceiver: BroadcastReceiver? = null
    private var screenLockReceiver: BroadcastReceiver? = null
    private var vpnFreezeInFlight = false
    private var vpnFreezeDoneForSession = false
    /** Main :vpnwatch dispatches [Utility.requestVpnBatchFreeze] at most once per VPN-up session. */
    private var mainVpnBatchFreezeDispatched = false
    private var config = AntiSpyWatchConfig()
    /** Остановлены намеренно: будить себя будильником нельзя, это и был цикл перезапуска. */
    private var stopping = false
    /** Триггер, по которому идет отсчет задержки перед заморозкой. */
    private var pendingFreeze: AntiSpyTrigger? = null

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(applicationContext)
        config = AntiSpyManager.readWatchConfig(this)
        Log.i(
            TAG,
            "onCreate pid=${Process.myPid()} work=${AntiSpyManager.isWorkProfile(this)} $config"
        )
        if (!config.watcherNeeded) {
            stopDeliberately("выключен в настройках")
            return
        }
        // Ресиверы регистрируются только после успешного переднего плана: иначе сервис
        // останавливается с уже зарегистрированными ресиверами и течет (IntentReceiverLeaked).
        if (!ensureForeground()) {
            stopDeliberately("передний план недоступен")
            return
        }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        registerFreezeCompleteReceiver()
        applyTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) {
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_PENDING_FREEZE) {
            dropPendingFreeze(byUser = true)
            return START_STICKY
        }
        config = AntiSpyManager.readWatchConfig(this)
        if (!config.watcherNeeded) {
            stopDeliberately("выключен в настройках")
            return START_NOT_STICKY
        }
        if (!ensureForeground()) {
            stopDeliberately("передний план недоступен")
            return START_NOT_STICKY
        }
        applyTriggers()
        return START_STICKY
    }

    /** Приводит подписки в соответствие настройкам; вызывается на каждом старте. */
    private fun applyTriggers() {
        if (config.freezeOnVpn) {
            registerVpnCallbacks()
            registerConnectivityReceiver()
            vpnPresent = VpnTunnelDetector.isVpnActive(this)
            Log.d(TAG, "initial vpn=$vpnPresent")
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, VPN_POLL_MS)
        } else {
            handler.removeCallbacks(pollRunnable)
            unregisterVpnCallbacks()
            connectivityReceiver = unregisterSafely(connectivityReceiver)
        }
        if (config.freezeOnScreenLock) {
            registerScreenLockReceiver()
        } else {
            screenLockReceiver = unregisterSafely(screenLockReceiver)
        }
        // Уже идущий отсчет обязан подчиниться новой настройке: увидев отсчет, пользователь
        // идет именно в настройки, и заморозить его приложения после этого нельзя.
        pendingFreeze?.let { trigger ->
            if (config.reactTo(trigger) != AntiSpyReaction.FREEZE_AFTER_DELAY) {
                dropPendingFreeze(byUser = false)
            }
        }
    }

    private fun pollVpnState() {
        val active = scanVpnActive()
        if (active && !vpnPresent) {
            if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
                Log.d(TAG, "poll: vpn active but reactions suppressed")
            } else {
                Log.i(TAG, "poll: vpn became active")
                onVpnStateChanged(true)
            }
        } else if (!active && vpnPresent) {
            onVpnStateChanged(false)
        } else if (active && !vpnFreezeDoneForSession &&
            !AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()
        ) {
            // Edge-based triggering misses VPN-already-up and network churn; keep retrying until
            // BatchFreezeService reports every auto-freeze app is hidden (foreground VPN client).
            onTrigger(AntiSpyTrigger.VPN_UP)
        }
        handler.postDelayed(pollRunnable, VPN_POLL_MS)
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) {
            return true
        }
        return try {
            // Служебное уведомление фонового сторожа: тихий канал, без звука, вибрации и
            // всплытия. Приложение просит IMPORTANCE_MIN, платформа поднимает канал
            // уведомления переднего плана до IMPORTANCE_LOW -- скрыть его нельзя.
            startForeground(
                NOTIFICATION_ID,
                Utility.buildNotification(
                    this,
                    getString(R.string.anti_spy_monitor_notification_title),
                    getString(R.string.anti_spy_monitor_notification_title),
                    getString(R.string.anti_spy_monitor_notification_text),
                    R.drawable.ic_lock_open,
                ),
            )
            foregroundStarted = true
            foregroundSinceMs = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun registerConnectivityReceiver() {
        if (connectivityReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (VpnTunnelDetector.isVpnActive(context)) {
                    Log.d(TAG, "CONNECTIVITY_ACTION vpn active")
                    onVpnStateChanged(true)
                }
            }
        }
        connectivityReceiver = registerLocalReceiver(
            receiver,
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
            "CONNECTIVITY_ACTION",
        )
    }

    private fun registerFreezeCompleteReceiver() {
        if (freezeCompleteReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Utility.ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE) {
                    return
                }
                vpnFreezeDoneForSession = true
                Log.i(TAG, "VPN batch-freeze session complete")
            }
        }
        freezeCompleteReceiver = registerLocalReceiver(
            receiver,
            IntentFilter(Utility.ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE),
            "freeze-complete",
        )
    }

    private fun registerScreenLockReceiver() {
        if (screenLockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.i(TAG, "screen locked")
                onTrigger(AntiSpyTrigger.SCREEN_LOCK)
            }
        }
        screenLockReceiver = registerLocalReceiver(
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            "screen-off",
        )
    }

    /** С API 34 флаг экспорта обязателен; оба ресивера слушают только себя и систему. */
    @Suppress("UnspecifiedRegisterReceiverFlag") // ниже API 33 флага экспорта не существует
    private fun registerLocalReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        name: String,
    ): BroadcastReceiver? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            receiver
        } catch (e: Exception) {
            Log.w(TAG, "$name receiver failed", e)
            null
        }
    }

    private fun unregisterSafely(receiver: BroadcastReceiver?): BroadcastReceiver? {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Колбэки сети приходят на внутреннем потоке ConnectivityManager. Все состояние сторожа
     * (отсчет заморозки, флаги сессии, настройки) живет на главном потоке, поэтому решение
     * принимается только там: иначе два потока одновременно заводят один и тот же отсчет.
     */
    private fun onMainThread(action: () -> Unit) {
        handler.post(action)
    }

    private fun registerVpnCallbacks() {
        val cm = connectivityManager ?: return

        if (vpnCallback == null) {
            val request = buildVpnNetworkRequest()
            vpnCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "vpn callback onAvailable")
                    onMainThread { onVpnStateChanged(true) }
                }

                override fun onLost(network: Network) {
                    onMainThread { onVpnStateChanged(scanVpnActive()) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    onMainThread { onVpnStateChanged(if (vpn) true else scanVpnActive()) }
                }
            }
            try {
                cm.registerNetworkCallback(request, vpnCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "registerNetworkCallback failed", e)
                vpnCallback = null
            }
        }

        if (defaultCallback == null) {
            defaultCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (vpn) {
                        Log.d(TAG, "default network vpn capabilities")
                        onMainThread { onVpnStateChanged(true) }
                    }
                }
            }
            try {
                cm.registerDefaultNetworkCallback(defaultCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "registerDefaultNetworkCallback failed", e)
                defaultCallback = null
            }
        }
    }

    private fun unregisterVpnCallbacks() {
        val cm = connectivityManager ?: return
        vpnCallback?.let { callback ->
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
            vpnCallback = null
        }
        defaultCallback?.let { callback ->
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
            defaultCallback = null
        }
    }

    private fun onVpnStateChanged(vpnActive: Boolean) {
        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            Log.d(TAG, "vpn state ignored during displacement")
            return
        }
        if (vpnActive == vpnPresent) return
        vpnPresent = vpnActive
        if (!vpnActive) {
            // Туннель упал -- морозить незачем, а сессия начинается заново.
            dropPendingFreeze(byUser = false)
            vpnFreezeDoneForSession = false
            mainVpnBatchFreezeDispatched = false
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            if (isMainProfileWatcher()) {
                postVpnStateAlert(R.string.anti_spy_vpn_alert_disconnected_text)
            }
            return
        }
        if (isMainProfileWatcher()) {
            postVpnStateAlert(R.string.anti_spy_vpn_alert_connected_text)
        }
        onTrigger(AntiSpyTrigger.VPN_UP)
    }

    private fun isMainProfileWatcher(): Boolean = !AntiSpyManager.isWorkProfile(this)

    private fun postVpnStateAlert(textResId: Int) {
        val title = getString(R.string.anti_spy_monitor_notification_title)
        val text = getString(textResId)
        ZindanToast.show(this, text)
        Utility.postUserAlert(this, VPN_STATE_NOTIFICATION_ID, title, text)
    }

    private fun onTrigger(trigger: AntiSpyTrigger) {
        when (config.reactTo(trigger)) {
            AntiSpyReaction.IGNORE -> Log.d(TAG, "trigger $trigger is off")
            AntiSpyReaction.NOTIFY_ONLY -> notifyInsteadOfFreezing(trigger)
            AntiSpyReaction.FREEZE_NOW -> {
                pendingFreeze = trigger
                handler.removeCallbacks(freezeRunnable)
                handler.post(freezeRunnable)
            }
            AntiSpyReaction.FREEZE_AFTER_DELAY -> scheduleDelayedFreeze(trigger)
        }
    }

    private fun scheduleDelayedFreeze(trigger: AntiSpyTrigger) {
        if (pendingFreeze != null) return
        pendingFreeze = trigger
        postFreezePendingAlert()
        handler.postDelayed(freezeRunnable, config.delaySeconds * 1000L)
        Log.i(TAG, "freeze in ${config.delaySeconds}s ($trigger)")
    }

    private fun runPendingFreeze() {
        val trigger = pendingFreeze ?: return
        pendingFreeze = null
        cancelNotification(FREEZE_PENDING_NOTIFICATION_ID)
        performFreeze(trigger)
    }

    /**
     * @param byUser отмена кнопкой держится до конца сессии VPN, иначе опрос вернет заморозку
     * через 2 с. Снятие из-за смены настроек сессию не закрывает: новое решение примет
     * следующий такт опроса, и в мягком режиме пользователь получит уведомление.
     */
    private fun dropPendingFreeze(byUser: Boolean) {
        val trigger = pendingFreeze ?: return
        handler.removeCallbacks(freezeRunnable)
        pendingFreeze = null
        cancelNotification(FREEZE_PENDING_NOTIFICATION_ID)
        if (byUser && trigger == AntiSpyTrigger.VPN_UP) {
            vpnFreezeDoneForSession = true
        }
        Log.i(TAG, "freeze dropped ($trigger, byUser=$byUser)")
    }

    private fun notifyInsteadOfFreezing(trigger: AntiSpyTrigger) {
        if (trigger == AntiSpyTrigger.VPN_UP) {
            if (vpnFreezeDoneForSession) return
            vpnFreezeDoneForSession = true
        }
        Utility.postUserAlert(
            this,
            VPN_STATE_NOTIFICATION_ID,
            getString(R.string.anti_spy_monitor_notification_title),
            getString(R.string.anti_spy_notify_only_text),
        )
    }

    private fun performFreeze(trigger: AntiSpyTrigger) {
        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            Log.d(TAG, "freeze skipped: dummy vpn cycle")
            return
        }
        if (trigger == AntiSpyTrigger.VPN_UP) {
            if (vpnFreezeDoneForSession) {
                return
            }
            if (!VpnTunnelDetector.isVpnActive(this)) {
                Log.d(TAG, "freeze cancelled: vpn no longer active")
                vpnPresent = false
                AntiSpyVpnPromptManager.onVpnSessionEnded()
                return
            }
            VpnTunnelDetector.logDiagnostics(this)
        }

        if (vpnFreezeInFlight) return
        vpnFreezeInFlight = true
        try {
            if (isMainProfileWatcher()) {
                if (trigger == AntiSpyTrigger.VPN_UP) {
                    if (mainVpnBatchFreezeDispatched) return
                    mainVpnBatchFreezeDispatched = true
                }
                Utility.requestVpnBatchFreeze(this)
                Log.i(TAG, "batch-freeze requested from MAIN watcher ($trigger)")
                postFreezeDiagnostic("MAIN: запрос заморозки ($trigger)")
                return
            }

            if (trigger == AntiSpyTrigger.VPN_UP) {
                Utility.requestVpnBatchFreeze(this)
            }
            val list = WorkProfileBatchFreeze.packagesForScope(this, config.scope)
            if (list.isEmpty()) {
                postFreezeDiagnostic("WORK: список пуст — открой Zindan один раз")
                return
            }
            val frozen = WorkProfileBatchFreeze.freezeList(this, list)
            val stillVisible = WorkProfileBatchFreeze.countStillVisible(this, list)
            Log.i(TAG, "freeze in work ($trigger): $frozen of ${list.size}, still=$stillVisible")
            if (stillVisible > 0) {
                postFreezeDiagnostic("WORK: не заморожено $stillVisible — повтор…")
                return
            }
            postFreezeDiagnostic("WORK: заморожено $frozen из ${list.size}")
            if (trigger == AntiSpyTrigger.VPN_UP) {
                vpnFreezeDoneForSession = true
                Utility.notifyVpnBatchFreezeSessionComplete(this, frozen > 0)
            }
        } finally {
            handler.postDelayed({ vpnFreezeInFlight = false }, 1500L)
        }
    }

    /** Отсчет до заморозки виден пользователю и отменяется кнопкой -- иначе реакция мгновенная. */
    private fun postFreezePendingAlert() {
        val notification = Utility.buildUserAlertNotification(
            this,
            getString(R.string.anti_spy_monitor_notification_title),
            getString(R.string.anti_spy_freeze_pending_text, config.delaySeconds),
        )
        notification.actions = arrayOf(
            Notification.Action.Builder(
                null,
                getString(R.string.anti_spy_freeze_cancel),
                cancelFreezeIntent(),
            ).build(),
        )
        getSystemService(NotificationManager::class.java)
            .notify(FREEZE_PENDING_NOTIFICATION_ID, notification)
    }

    private fun cancelFreezeIntent(): PendingIntent {
        val intent = Intent(this, AntiSpyVpnWatchService::class.java)
            .setAction(ACTION_CANCEL_PENDING_FREEZE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, REQUEST_CANCEL_FREEZE, intent, flags)
        } else {
            PendingIntent.getService(this, REQUEST_CANCEL_FREEZE, intent, flags)
        }
    }

    /** Диагностика сторожа нужна в шторке, но не звуком: тот же тихий канал, что у сервиса. */
    private fun postFreezeDiagnostic(text: String) {
        val id = if (isMainProfileWatcher()) DIAG_MAIN_NOTIFICATION_ID else DIAG_WORK_NOTIFICATION_ID
        val title = getString(R.string.anti_spy_monitor_notification_title)
        getSystemService(NotificationManager::class.java).notify(
            id,
            Utility.buildNotification(this, title, title, text, R.drawable.ic_lock_open),
        )
    }

    private fun cancelNotification(id: Int) {
        getSystemService(NotificationManager::class.java).cancel(id)
    }

    private fun scanVpnActive(): Boolean = VpnTunnelDetector.isVpnActive(this)

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved, scheduling restart")
        scheduleRestartIfNeeded()
    }

    private fun stopDeliberately(reason: String) {
        Log.i(TAG, "stopping: $reason")
        stopping = true
        cancelScheduledRestart(applicationContext)
        // Поднять нас могли через startForegroundService, и остановка без единого
        // startForeground -- это не тихий выход, а ForegroundServiceDidNotStartInTimeException
        // (замер на этом же сервисе: logs/phase5-d3-after-main.log). Уведомление снимаем сразу.
        if (ensureForeground()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        scheduleRestartIfNeeded()
        handler.removeCallbacks(freezeRunnable)
        handler.removeCallbacks(pollRunnable)
        pendingFreeze = null
        cancelNotification(FREEZE_PENDING_NOTIFICATION_ID)
        connectivityReceiver = unregisterSafely(connectivityReceiver)
        freezeCompleteReceiver = unregisterSafely(freezeCompleteReceiver)
        screenLockReceiver = unregisterSafely(screenLockReceiver)
        unregisterVpnCallbacks()
        super.onDestroy()
    }

    /**
     * Прежний код будил себя через 1500 мс после любой смерти, включая смерть по неустранимой
     * причине, и крутился в цикле. Теперь перезапуск только при живой настройке, с растущим
     * откатом и пределом попыток.
     */
    private fun scheduleRestartIfNeeded() {
        if (stopping) {
            return
        }
        // Настройку читаем заново: остановить нас мог stopService из другого процесса, и
        // тогда поле config хранит то, что было до выключения переключателя.
        if (!AntiSpyManager.readWatchConfig(this).watcherNeeded) {
            Log.i(TAG, "restart skipped: watcher is off")
            return
        }
        if (foregroundStarted &&
            SystemClock.elapsedRealtime() - foregroundSinceMs > RESTART_BUDGET_RESET_MS
        ) {
            restartAttempts = 0
        }
        val delay = AntiSpyWatchRestart.delayMsForAttempt(restartAttempts)
        if (delay == null) {
            Log.w(TAG, "restart budget spent after $restartAttempts attempts, giving up")
            return
        }
        restartAttempts++
        scheduleRestart(applicationContext, delay)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AntiSpyVpnWatch"
        private const val NOTIFICATION_ID = 0xe49d0
        private const val VPN_STATE_NOTIFICATION_ID = 0xe49d1
        private const val FREEZE_PENDING_NOTIFICATION_ID = 0xe49d6
        private const val DIAG_MAIN_NOTIFICATION_ID = 0xe49da
        private const val DIAG_WORK_NOTIFICATION_ID = 0xe49db
        private const val VPN_POLL_MS = 2000L
        private const val RESTART_REQUEST_CODE = 0xE49D2
        private const val REQUEST_CANCEL_FREEZE = 0xE49D5
        /** Столько сторож должен прожить на переднем плане, чтобы попытки начались заново. */
        private const val RESTART_BUDGET_RESET_MS = 120_000L

        const val ACTION_CANCEL_PENDING_FREEZE =
            "io.gatekeeper.action.CANCEL_PENDING_FREEZE"

        /** Общий на процесс: сервис умирает и рождается заново, счетчик обязан переживать это. */
        @Volatile
        private var restartAttempts = 0

        fun syncState(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, AntiSpyVpnWatchService::class.java)
            if (!AntiSpyManager.readWatchConfig(app).watcherNeeded) {
                cancelScheduledRestart(app)
                try {
                    app.stopService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "stopService failed", e)
                }
                Log.d(TAG, "syncState: watcher is off")
                return
            }
            restartAttempts = 0
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                Log.d(TAG, "syncState: start requested pid=${Process.myPid()}")
            } catch (e: SecurityException) {
                Log.w(TAG, "FGS start denied", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "FGS start failed", e)
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                ) {
                    Log.w(TAG, "FGS not allowed from background; will retry from foreground", e)
                } else {
                    throw e
                }
            }
        }

        private fun restartIntent(app: Context): PendingIntent {
            val intent = Intent(app, AntiSpyVpnWatchService::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(app, RESTART_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(app, RESTART_REQUEST_CODE, intent, flags)
            }
        }

        private fun scheduleRestart(app: Context, delayMs: Long) {
            try {
                val am = app.getSystemService(AlarmManager::class.java) ?: return
                val trigger = SystemClock.elapsedRealtime() + delayMs
                // setAndAllowWhileIdle is inexact and does NOT require SCHEDULE_EXACT_ALARM /
                // USE_EXACT_ALARM, which newer Android no longer grants by default.
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, restartIntent(app)
                )
                Log.i(TAG, "restart scheduled in $delayMs ms")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleRestart failed", e)
            }
        }

        private fun cancelScheduledRestart(app: Context) {
            try {
                app.getSystemService(AlarmManager::class.java)?.cancel(restartIntent(app))
            } catch (e: Exception) {
                Log.w(TAG, "cancelScheduledRestart failed", e)
            }
        }

        private fun buildVpnNetworkRequest(): NetworkRequest {
            return try {
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            } catch (_: IllegalArgumentException) {
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build()
            }
        }
    }
}
