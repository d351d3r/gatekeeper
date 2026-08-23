package io.gatekeeper.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.gatekeeper.util.PackageEntry
import io.gatekeeper.util.PackageScanFilter
import io.gatekeeper.util.Utility

/**
 * Work-profile periodic scan: enumerates third-party packages and reports the full current
 * set to the personal profile, where AutoFreezeDefaults diffs it against its baseline.
 * Replaces the dead manifest ACTION_PACKAGE_ADDED path (targetSdk 35 implicit broadcast ban).
 */
class WorkPackageScanJobService : JobService() {
    private var worker: HandlerThread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!Utility.isProfileOwner(this)) {
            return false
        }
        worker = HandlerThread("WorkPackageScan").apply { start() }
        Handler(worker!!.looper).post {
            try {
                val pm = packageManager
                val entries = pm.getInstalledApplications(0).map {
                    PackageEntry(
                        packageName = it.packageName,
                        isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isInstalled = true,
                    )
                }
                val packages = PackageScanFilter.selectScannedPackages(packageName, entries)
                Utility.scheduleWorkPackageReportOnMainProfile(applicationContext, packages)
            } catch (e: Exception) {
                Log.w(TAG, "work package scan failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.quitSafely()
        worker = null
        return false
    }

    companion object {
        private const val TAG = "WorkPackageScan"
        private const val JOB_ID = 0xE49F0
        private const val PERIOD_MS = 15L * 60_000L
        private const val FLEX_MS = 5L * 60_000L

        fun ensureScheduled(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val pending = scheduler.getPendingJob(JOB_ID)
            if (pending != null && pending.isPersisted) {
                return
            }
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, WorkPackageScanJobService::class.java)
            )
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(info)
            Log.i(TAG, "scan job scheduled: $result")
        }
    }
}
