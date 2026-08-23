package io.gatekeeper.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.Utility

/**
 * Personal-profile consumer of the work-package report produced by the periodic scan job and
 * the runtime install receiver in the work profile (see [WorkProfilePackageAddedReceiver]
 * fallback history: manifest PACKAGE_ADDED delivery is blocked at targetSdk 35).
 */
class WorkPackageReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Utility.ACTION_WORK_PACKAGES_REPORT) {
            return
        }
        val pendingResult = goAsync()
        try {
            val app = context.applicationContext
            if (AntiSpyManager.isWorkProfile(app)) {
                // PendingIntent/broadcast may resolve in the work user — bounce to personal.
                Log.d(TAG, "forward work packages report to personal profile")
                val packages = intent.getStringArrayExtra("work_packages") ?: return
                Utility.scheduleWorkPackageReportOnMainProfile(app, packages)
                return
            }
            if (!AuthenticationUtility.checkIntent(intent)) {
                Log.w(TAG, "work packages report rejected: bad signature or timestamp")
                return
            }
            val packages = intent.getStringArrayExtra("work_packages") ?: return
            val changed = AutoFreezeDefaults.applyDefaultsForNewPackages(app, null, packages.toList())
            if (changed) {
                val list = AntiSpyManager.getAutoFreezeList(app)
                if (!Utility.startBatchFreezeInWorkProfile(app, list)) {
                    Log.w(TAG, "batch freeze after package report did not start")
                }
                Utility.deliverAppListRefreshInMainProcess(app)
            }
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "WorkPackageReport"
    }
}
