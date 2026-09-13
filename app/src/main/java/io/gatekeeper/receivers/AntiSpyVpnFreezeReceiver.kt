package io.gatekeeper.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.gatekeeper.R
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.CrossProfileScheduler
import io.gatekeeper.util.Utility

/**
 * Runs VPN-up batch freeze in the main app process (not {@code :vpnwatch}).
 * Cross-profile delivery to work profile is more reliable from here than from the VPN FGS process.
 */
class AntiSpyVpnFreezeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val pendingResult = goAsync()
        try {
            handleVpnBatchFreeze(context.applicationContext)
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleVpnBatchFreeze(app: Context) {
        LocalStorageManager.initialize(app)
        if (AntiSpyManager.isWorkProfile(app)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleElapsedMs < HANDLE_DEBOUNCE_MS) {
            Log.d(TAG, "VPN batch freeze: debounced")
            return
        }
        lastHandleElapsedMs = now

        val list = Utility.normalizeStringList(AntiSpyManager.getAutoFreezeList(app))
        if (list.isEmpty()) {
            Log.w(TAG, "VPN batch freeze: auto-freeze list is empty")
            Notifications.postUserAlert(
                app,
                WARN_NOTIFICATION_ID,
                app.getString(R.string.notif_freeze_list_empty_title),
                app.getString(R.string.notif_freeze_list_empty_text),
                Notifications.CHANNEL_WARNING,
            )
            return
        }
        Log.i(TAG, "VPN batch freeze requested, list=${list.size}")
        // Work :vpnwatch performs DPM freeze; receiver is a one-shot fallback when work list is stale.
        val launched = CrossProfileScheduler.launchFreezeInWorkProfile(app, list, vpnOrigin = true)
        val started = CrossProfileScheduler.startBatchFreezeInWorkProfile(app, list)
        if (!launched && !started) {
            CrossProfileScheduler.scheduleFreezeInWorkProfile(app, list, vpnOrigin = true)
            Log.w(TAG, "VPN batch freeze: cross-profile delivery failed, AlarmManager fallback")
        }
        Log.i(TAG, "VPN batch freeze dispatched, serviceInWork=$started")
    }

    companion object {
        private const val TAG = "AntiSpyVpnFreeze"
        private const val WARN_NOTIFICATION_ID = 0xe49dc
        private const val HANDLE_DEBOUNCE_MS = 5000L
        @Volatile
        private var lastHandleElapsedMs = 0L
        const val ACTION = "io.gatekeeper.action.VPN_BATCH_FREEZE"
    }
}
