package io.gatekeeper.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.gatekeeper.R
import io.gatekeeper.receivers.AppListRefreshReceiver
import io.gatekeeper.ui.AppListFragment
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.MainActivity

private const val NOTIFIER_TAG = "ProfileNotifier"
private const val WAKEUP_DELAY_MS = 50L
private const val APP_LIST_REFRESH_DELAY_MS = 700L
private const val REFRESH_FOLLOWUP_SLOW_MS = 2000L
private const val REFRESH_FOLLOWUP_LATE_MS = 4500L
private const val REQ_REFRESH_MAIN_BASE = 0xE49E8
private const val REQ_REFRESH_RECEIVER = 0xE49E9
private const val REQ_TOAST_BASE = 0xE49E6

private val APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS =
    longArrayOf(APP_LIST_REFRESH_DELAY_MS, REFRESH_FOLLOWUP_SLOW_MS, REFRESH_FOLLOWUP_LATE_MS)
private val APP_LIST_REFRESH_DELIVERY_DELAYS_MS =
    longArrayOf(WAKEUP_DELAY_MS, APP_LIST_REFRESH_DELAY_MS, REFRESH_FOLLOWUP_SLOW_MS, REFRESH_FOLLOWUP_LATE_MS)

private fun appListRefreshReceiverIntent(context: Context): Intent =
    Intent(AppListRefreshReceiver.ACTION).apply {
        setPackage(context.packageName)
        component = ComponentName(context, AppListRefreshReceiver::class.java)
    }

private fun refreshMainAppListIntent(context: Context, fromWork: Boolean): Intent =
    Intent(DummyActivity.REFRESH_MAIN_APP_LIST).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (fromWork) {
            Utility.transferIntentToProfile(context, this)
        } else {
            setClass(context, DummyActivity::class.java)
        }
    }

private fun toastIntent(context: Context, fromWork: Boolean, toastResId: Int): Intent =
    Intent(DummyActivity.SHOW_TOAST).apply {
        putExtra(MainActivity.EXTRA_TOAST_RES_ID, toastResId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (fromWork) {
            Utility.transferIntentToProfile(context, this)
        } else {
            component = ComponentName(context, DummyActivity::class.java)
        }
    }

/** AlarmManager one-shot wakeup delivering [intent] as an Activity. */
private fun scheduleActivityWakeup(
    context: Context,
    intent: Intent,
    requestCode: Int,
    delayMs: Long,
): Boolean {
    val pi = PendingIntents.activity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val am = context.getSystemService(AlarmManager::class.java) ?: return false
    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    return true
}

/** AlarmManager one-shot wakeup delivering [intent] as a Broadcast. */
private fun scheduleBroadcastWakeup(
    context: Context,
    intent: Intent,
    requestCode: Int,
    delayMs: Long,
): Boolean {
    val pi = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val am = context.getSystemService(AlarmManager::class.java) ?: return false
    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    return true
}

/**
 * Доставка уведомлений «обнови списки приложений / покажи тост» на личную
 * сторону после кросс-профильной заморозки/разморозки: AlarmManager-вейкапы
 * через релей + локальные broadcast'ы в текущем процессе. Вынесено из Utility.
 */
object ProfileNotifier {
    /** Refresh app lists after a cross-profile freeze/unfreeze DummyActivity finishes. */
    fun scheduleAppListRefresh(
        context: Context,
        delaysMs: LongArray = longArrayOf(APP_LIST_REFRESH_DELAY_MS),
    ) {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        for (delay in delaysMs) {
            handler.postDelayed({
                LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(Intent("io.gatekeeper.broadcast.REFRESH"))
            }, delay)
        }
    }

    /**
     * After a background VPN batch-freeze in the work profile: refresh both app-list tabs on the
     * personal profile (with follow-up delays for slow PackageManager updates) and optionally
     * show the success toast there.
     */
    fun notifyBatchFreezeComplete(context: Context, showSuccessToast: Boolean) {
        scheduleAppListRefreshDelivery(context.applicationContext)
        if (showSuccessToast) {
            scheduleShowToastOnMainProfile(
                context.applicationContext,
                R.string.freeze_all_success,
            )
        }
    }

    /** Refresh both app-list tabs on the personal profile after work-profile freeze/unfreeze. */
    fun scheduleAppListRefreshOnMainProfile(context: Context) {
        scheduleAppListRefreshDelivery(context.applicationContext)
    }

    /** Work profile → personal: run [AppListRefreshReceiver] in the default app process. */
    fun scheduleAppListRefreshReceiverOnMainProfile(context: Context) {
        try {
            if (scheduleBroadcastWakeup(
                    context,
                    appListRefreshReceiverIntent(context),
                    REQ_REFRESH_RECEIVER,
                    WAKEUP_DELAY_MS,
                )
            ) {
                Log.i(NOTIFIER_TAG, "scheduled app-list refresh receiver on main profile")
            }
        } catch (e: SecurityException) {
            Log.w(NOTIFIER_TAG, "scheduleAppListRefreshReceiverOnMainProfile denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(NOTIFIER_TAG, "scheduleAppListRefreshReceiverOnMainProfile: bad pending intent", e)
        }
    }

    /**
     * Deliver app-list refresh to the personal profile UI.
     * From the work profile / {@code :vpnwatch}: cross-profile [DummyActivity.REFRESH_MAIN_APP_LIST].
     */
    fun scheduleAppListRefreshDelivery(context: Context) {
        scheduleRefreshMainAppList(context.applicationContext)
    }

    /** Immediate refresh in the personal profile UI process. */
    fun deliverAppListRefreshInMainProcess(context: Context) {
        val app = context.applicationContext
        if (AntiSpyManager.isWorkProfile(app)) {
            scheduleRefreshMainAppList(app)
            return
        }
        MainActivity.refreshIfVisible()
        try {
            LocalBroadcastManager.getInstance(app)
                .sendBroadcast(Intent(AppListFragment.BROADCAST_REFRESH))
        } catch (e: SecurityException) {
            Log.w(NOTIFIER_TAG, "local app-list refresh broadcast denied", e)
        }
        try {
            val intent = Intent(app, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REFRESH_APP_LISTS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            app.startActivity(intent)
        } catch (e: SecurityException) {
            Log.w(NOTIFIER_TAG, "startActivity app-list refresh denied", e)
        } catch (e: ActivityNotFoundException) {
            Log.w(NOTIFIER_TAG, "startActivity app-list refresh: no target", e)
        }
    }

    /**
     * Тост на личный профиль после завершения фоновой заморозки. В личном
     * профиле без profile-owner показывает локально; иначе — через релей.
     */
    fun showToastOnMainProfile(context: Context, resId: Int) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isProfileOwnerApp(context.packageName)) {
            GatekeeperToast.show(context, resId)
            scheduleAppListRefresh(context, APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS)
            return
        }
        scheduleShowToastOnMainProfile(context.applicationContext, resId)
    }

    private fun scheduleRefreshMainAppList(context: Context) {
        val app = context.applicationContext
        val fromWork = AntiSpyManager.isWorkProfile(app)
        try {
            var requestCode = REQ_REFRESH_MAIN_BASE
            for (delay in APP_LIST_REFRESH_DELIVERY_DELAYS_MS) {
                val intent = refreshMainAppListIntent(app, fromWork)
                scheduleActivityWakeup(app, intent, requestCode++, delay)
            }
            Log.i(
                NOTIFIER_TAG,
                "scheduled personal-profile app-list refresh (work=$fromWork, " +
                    "${APP_LIST_REFRESH_DELIVERY_DELAYS_MS.size} delays)",
            )
        } catch (e: SecurityException) {
            Log.w(NOTIFIER_TAG, "scheduleRefreshMainAppList denied", e)
            fallbackRefresh(app, fromWork)
        } catch (e: IllegalArgumentException) {
            Log.w(NOTIFIER_TAG, "scheduleRefreshMainAppList: bad pending intent", e)
            fallbackRefresh(app, fromWork)
        }
    }

    private fun fallbackRefresh(app: Context, fromWork: Boolean) {
        if (fromWork) {
            scheduleAppListRefreshReceiverOnMainProfile(app)
        } else {
            deliverAppListRefreshInMainProcess(app)
        }
    }

    /** Cross-profile delivery of [DummyActivity.SHOW_TOAST] via AlarmManager (toast only). */
    private fun scheduleShowToastOnMainProfile(
        context: Context,
        toastResId: Int,
        delaysMs: LongArray = longArrayOf(WAKEUP_DELAY_MS),
    ) {
        val app = context.applicationContext
        val fromWork = AntiSpyManager.isWorkProfile(app)
        try {
            var requestCode = REQ_TOAST_BASE
            for (delay in delaysMs) {
                val intent = toastIntent(app, fromWork, toastResId)
                scheduleActivityWakeup(app, intent, requestCode++, delay)
            }
            Log.i(
                NOTIFIER_TAG,
                "scheduled main-profile toast (work=$fromWork, toast=$toastResId)",
            )
        } catch (e: SecurityException) {
            Log.w(NOTIFIER_TAG, "scheduleShowToastOnMainProfile denied", e)
            if (!fromWork) {
                scheduleAppListRefresh(app, APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(NOTIFIER_TAG, "scheduleShowToastOnMainProfile: bad pending intent", e)
            if (!fromWork) {
                scheduleAppListRefresh(app, APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS)
            }
        }
    }
}
