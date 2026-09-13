package io.gatekeeper.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.gatekeeper.receivers.AntiSpyVpnFreezeReceiver
import io.gatekeeper.services.BatchFreezeService
import io.gatekeeper.ui.DummyActivity

private const val SCHEDULER_TAG = "CrossProfileScheduler"
private const val WAKEUP_DELAY_MS = 50L
private const val REQ_FREEZE_ALL_ACTIVITY = 0xE49E1
private const val REQ_VPN_BATCH_RECEIVER = 0xE49E4
private const val REQ_VPN_BATCH_ON_MAIN = 0xE49E5
private const val REQ_VPN_SESSION_COMPLETE = 0xE49E7

private fun freezeAllInListIntent(list: Array<String>, vpnOrigin: Boolean): Intent =
    Intent(DummyActivity.FREEZE_ALL_IN_LIST).apply {
        putExtra("list", list)
        putExtra(DummyActivity.EXTRA_VPN_ORIGIN, vpnOrigin)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

private fun vpnBatchFreezeReceiverIntent(context: Context): Intent =
    Intent(AntiSpyVpnFreezeReceiver.ACTION).apply {
        setPackage(context.packageName)
        component = ComponentName(context, AntiSpyVpnFreezeReceiver::class.java)
    }

private fun sendVpnBatchFreezeBroadcast(context: Context) {
    try {
        context.sendBroadcast(vpnBatchFreezeReceiverIntent(context))
        Log.i(SCHEDULER_TAG, "VPN batch freeze broadcast sent")
    } catch (e: SecurityException) {
        Log.w(SCHEDULER_TAG, "VPN batch freeze broadcast denied", e)
    }
}

private fun createContextAsUser(context: Context, user: UserHandle): Context =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val method = Context::class.java.getMethod(
            "createContextAsUser",
            UserHandle::class.java,
            Int::class.javaPrimitiveType,
        )
        method.invoke(context, user, 0) as Context
    } else {
        val method = Context::class.java.getMethod(
            "createPackageContextAsUser",
            String::class.java,
            Int::class.javaPrimitiveType,
            UserHandle::class.java,
        )
        method.invoke(context, context.packageName, 0, user) as Context
    }

private fun tryStartForegroundServiceAsUser(
    context: Context,
    intent: Intent,
    profile: UserHandle,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return false
    }
    return try {
        val startFgAsUser = Context::class.java.getMethod(
            "startForegroundServiceAsUser",
            Intent::class.java,
            UserHandle::class.java,
        )
        startFgAsUser.invoke(context, intent, profile)
        Log.i(SCHEDULER_TAG, "startForegroundServiceAsUser ok: ${intent.component}")
        true
    } catch (e: ReflectiveOperationException) {
        Log.d(SCHEDULER_TAG, "startForegroundServiceAsUser unavailable, fallback", e)
        false
    } catch (e: SecurityException) {
        Log.w(SCHEDULER_TAG, "startForegroundServiceAsUser denied", e)
        false
    }
}

private fun tryStartServiceAsUser(context: Context, intent: Intent, profile: UserHandle): Boolean =
    try {
        val startAsUser = Context::class.java.getMethod(
            "startServiceAsUser",
            Intent::class.java,
            UserHandle::class.java,
        )
        startAsUser.invoke(context, intent, profile)
        Log.i(SCHEDULER_TAG, "startServiceAsUser ok: ${intent.component}")
        true
    } catch (e: ReflectiveOperationException) {
        Log.d(SCHEDULER_TAG, "startServiceAsUser unavailable, trying profile context", e)
        false
    } catch (e: SecurityException) {
        Log.w(SCHEDULER_TAG, "startServiceAsUser denied", e)
        false
    }

private fun tryStartServiceViaProfileContext(
    context: Context,
    intent: Intent,
    profile: UserHandle,
): Boolean =
    try {
        val profileContext = createContextAsUser(context, profile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            profileContext.startForegroundService(intent)
        } else {
            profileContext.startService(intent)
        }
        Log.i(SCHEDULER_TAG, "startService in managed profile ok: ${intent.component}")
        true
    } catch (e: ReflectiveOperationException) {
        Log.w(SCHEDULER_TAG, "profile context unavailable for $profile", e)
        false
    } catch (e: SecurityException) {
        Log.w(SCHEDULER_TAG, "startService in managed profile denied for $profile", e)
        false
    } catch (e: IllegalStateException) {
        Log.w(SCHEDULER_TAG, "startService in managed profile blocked for $profile", e)
        false
    }

/**
 * Планирование кросс-профильной заморозки: запуск заморозки в рабочем
 * профиле (через релей-Activity или фоновый сервис, с reflection-созданием
 * контекста целевого юзера как фолбэком) и пробуждение исполнителей
 * VPN-заморозки. Вынесено из Utility.
 */
object CrossProfileScheduler {
    const val ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE =
        "io.gatekeeper.action.VPN_BATCH_FREEZE_SESSION_COMPLETE"

    /**
     * @param vpnOrigin true только для фоновой заморозки по VPN: в этом случае
     * исполнитель в рабочем профиле постит уведомление в его шторку (пользователь
     * может быть где угодно). Ручной запуск уведомление не постит — вместо него
     * тост на личный профиль (E-3).
     */
    fun launchFreezeInWorkProfile(
        context: Context,
        list: Array<String>,
        vpnOrigin: Boolean = false,
    ): Boolean {
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            return false
        }
        return try {
            val intent = freezeAllInListIntent(normalized, vpnOrigin)
            Utility.transferIntentToProfile(context, intent)
            context.startActivity(intent)
            Log.i(SCHEDULER_TAG, "launch work-profile freeze for ${normalized.size} apps")
            true
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "launchFreezeInWorkProfile denied", e)
            false
        } catch (e: ActivityNotFoundException) {
            Log.w(SCHEDULER_TAG, "launchFreezeInWorkProfile: no relay target", e)
            false
        }
    }

    /** Background delivery: start [BatchFreezeService] in the work profile (no Activity). */
    fun startBatchFreezeInWorkProfile(context: Context, list: Array<String>): Boolean {
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            return false
        }
        val intent = BatchFreezeService.buildIntent(context.applicationContext, normalized)
        return startServiceInManagedProfile(context.applicationContext, intent)
    }

    /** Start a service in the managed (work) user from the parent profile. */
    fun startServiceInManagedProfile(context: Context, intent: Intent): Boolean {
        val um = context.getSystemService(UserManager::class.java)
        val self = Process.myUserHandle()
        val managed = um?.userProfiles?.firstOrNull { it != self } ?: return false
        return tryStartForegroundServiceAsUser(context, intent, managed) ||
            tryStartServiceAsUser(context, intent, managed) ||
            tryStartServiceViaProfileContext(context, intent, managed)
    }

    /**
     * Fallback background delivery via `AlarmManager` when cross-profile
     * `startService` is unavailable (may still be blocked on some OEMs).
     */
    fun scheduleFreezeInWorkProfile(
        context: Context,
        list: Array<String>,
        vpnOrigin: Boolean = false,
    ) {
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            return
        }
        try {
            val intent = freezeAllInListIntent(normalized, vpnOrigin)
            Utility.transferIntentToProfile(context, intent)
            if (AlarmWakeups.activity(context, intent, REQ_FREEZE_ALL_ACTIVITY, WAKEUP_DELAY_MS)) {
                Log.i(SCHEDULER_TAG, "scheduled work-profile freeze for ${normalized.size} apps")
            }
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "scheduleFreezeInWorkProfile denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(SCHEDULER_TAG, "scheduleFreezeInWorkProfile: bad pending intent", e)
        }
    }

    /**
     * VPN-up batch freeze using the authoritative main-profile auto-freeze list.
     * Safe from the `:vpnwatch` FGS in either profile; delivery runs in the default app process.
     */
    fun requestVpnBatchFreeze(context: Context) {
        val app = context.applicationContext
        if (AntiSpyManager.isWorkProfile(app)) {
            scheduleVpnBatchFreezeOnMainProfile(app)
            return
        }
        // `:vpnwatch` cannot reliably start cross-profile work from the main process; wake the
        // default app process where [AntiSpyVpnFreezeReceiver] runs.
        scheduleVpnBatchFreezeInAppProcess(app)
        sendVpnBatchFreezeBroadcast(app)
    }

    /** Schedule [AntiSpyVpnFreezeReceiver] in the default app process (same user). */
    private fun scheduleVpnBatchFreezeInAppProcess(context: Context) {
        try {
            if (AlarmWakeups.broadcast(
                    context,
                    vpnBatchFreezeReceiverIntent(context),
                    REQ_VPN_BATCH_RECEIVER,
                    WAKEUP_DELAY_MS,
                )
            ) {
                Log.i(SCHEDULER_TAG, "scheduled VPN batch freeze in app process")
            }
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnBatchFreezeInAppProcess denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnBatchFreezeInAppProcess: bad pending intent", e)
        }
    }

    /** Work profile → main profile when background broadcast is blocked. */
    fun scheduleVpnBatchFreezeOnMainProfile(context: Context) {
        try {
            val intent = vpnBatchFreezeReceiverIntent(context)
            Utility.transferIntentToProfile(context, intent)
            if (AlarmWakeups.broadcast(context, intent, REQ_VPN_BATCH_ON_MAIN, WAKEUP_DELAY_MS)) {
                Log.i(SCHEDULER_TAG, "scheduled VPN batch freeze on main profile")
            }
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnBatchFreezeOnMainProfile denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnBatchFreezeOnMainProfile: bad pending intent", e)
        }
    }

    /** After a background VPN batch-freeze: refresh UI + toast + завершение сессии VPN-сторожа. */
    fun notifyVpnBatchFreezeSessionComplete(context: Context, showSuccessToast: Boolean) {
        ProfileNotifier.notifyBatchFreezeComplete(context, showSuccessToast)
        deliverVpnBatchFreezeSessionComplete(context.applicationContext)
        if (AntiSpyManager.isWorkProfile(context)) {
            scheduleVpnSessionCompleteOnMainProfile(context.applicationContext)
        }
    }

    /** Broadcast [ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE] in the current user/profile. */
    fun deliverVpnBatchFreezeSessionComplete(context: Context) {
        try {
            val intent = Intent(ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE).apply {
                setPackage(context.packageName)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.i(SCHEDULER_TAG, "VPN batch-freeze session complete broadcast sent")
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "VPN batch-freeze session complete broadcast denied", e)
        }
    }

    /** Work profile → personal: wake main :vpnwatch via cross-profile Activity (Samsung-safe). */
    private fun scheduleVpnSessionCompleteOnMainProfile(context: Context) {
        try {
            val intent = Intent(DummyActivity.VPN_SESSION_COMPLETE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            Utility.transferIntentToProfile(context, intent)
            if (AlarmWakeups.activity(context, intent, REQ_VPN_SESSION_COMPLETE, WAKEUP_DELAY_MS)) {
                Log.i(SCHEDULER_TAG, "scheduled VPN session complete on main profile")
            }
        } catch (e: SecurityException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnSessionCompleteOnMainProfile denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(SCHEDULER_TAG, "scheduleVpnSessionCompleteOnMainProfile: bad pending intent", e)
        }
    }
}
