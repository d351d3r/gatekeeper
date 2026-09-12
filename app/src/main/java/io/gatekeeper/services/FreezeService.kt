package io.gatekeeper.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import io.gatekeeper.R
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.ScreenLockFreezeScope
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkProfileBatchFreeze
import java.util.Date

// Экранный сторож: единственный владелец ACTION_SCREEN_OFF. Морозит выбранную
// область (запущенные за сеанс / список автозаморозки / все сторонние) после
// единой задержки. Сторож VPN больше экраном не занимается (редизайн, шаг 5).
class FreezeService : Service() {
    private var usageStats: Map<String, UsageStats> = HashMap()
    private var screenLockTime: Long = -1
    private var pendingScope: ScreenLockFreezeScope = ScreenLockFreezeScope.SESSION
    private lateinit var alarmManager: AlarmManager

    private val freezeWork: AlarmManager.OnAlarmListener = AlarmManager.OnAlarmListener {
        synchronized(FreezeService::class.java) {
            unregisterReceiver(unlockReceiver)

            val scope = pendingScope
            if (scope == ScreenLockFreezeScope.SESSION) {
                freezeSessionApps()
            } else {
                WorkProfileBatchFreeze.freezeList(
                    this,
                    WorkProfileBatchFreeze.packagesForScreenLockScope(this, scope),
                )
            }
            stopSelf()
        }
    }

    /** Сеансовая область: только то, что запустили через «Разморозить и запустить». */
    private fun freezeSessionApps() {
        if (appToFreeze.isEmpty()) return
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this, GatekeeperDeviceAdminReceiver::class.java)
        for (app in appToFreeze) {
            var shouldFreeze = true
            val stats = usageStats[app]
            if (stats != null &&
                screenLockTime - stats.lastTimeUsed <= APP_INACTIVE_TIMEOUT &&
                stats.totalTimeInForeground >= APP_INACTIVE_TIMEOUT
            ) {
                shouldFreeze = false
            }

            if (shouldFreeze) {
                dpm.setApplicationHidden(adminComponent, app, true)
            }
        }
        appToFreeze.clear()
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            alarmManager.cancel(freezeWork)
        }
    }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenLockTime = Date().time
            pendingScope = SettingsManager.getInstance().getAutoFreezeScope()
            if (pendingScope == ScreenLockFreezeScope.SESSION &&
                SettingsManager.getInstance().getSkipForegroundEnabled() &&
                Utility.checkUsageStatsPermission(this@FreezeService)
            ) {
                val usm = getSystemService(UsageStatsManager::class.java)
                usageStats = usm.queryAndAggregateUsageStats(
                    screenLockTime - APP_INACTIVE_TIMEOUT,
                    screenLockTime,
                )
            }

            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() +
                    SettingsManager.getInstance().getAutoFreezeDelay().toLong() * 1000,
                null,
                freezeWork,
                null,
            )
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        }
    }

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(AlarmManager::class.java)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(lockReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setForeground() {
        val notification = Utility.buildNotification(
            this,
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_desc),
            R.drawable.ic_lock_open,
        )

        // Уведомление без кнопки лучше, чем упавший в onCreate сервис заморозки.
        val intentFreeze = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        if (Utility.tryTransferIntentToProfileUnsigned(this, intentFreeze)) {
            notification.actions = arrayOf(
                Notification.Action.Builder(
                    null,
                    getString(R.string.service_auto_freeze_now),
                    PendingIntent.getActivity(
                        this,
                        0,
                        intentFreeze,
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private val appToFreeze = ArrayList<String>()

        private const val APP_INACTIVE_TIMEOUT = 1000L
        private const val NOTIFICATION_ID = 0xe49c0

        @Synchronized
        fun registerAppToFreeze(app: String) {
            if (!appToFreeze.contains(app)) {
                appToFreeze.add(app)
            }
        }

        @Synchronized
        fun hasPendingAppToFreeze(): Boolean = appToFreeze.isNotEmpty()
    }
}
