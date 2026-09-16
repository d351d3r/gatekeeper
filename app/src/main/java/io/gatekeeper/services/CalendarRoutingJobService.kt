package io.gatekeeper.services

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.gatekeeper.util.CalendarRouting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CalendarRoutingJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val run = JobRun(params)
        runs.put(params.jobId, run)?.stopped?.set(true)
        Thread {
            try {
                synchronized(WORK_LOCK) {
                    if (!run.stopped.get()) {
                        val moved = CalendarRouting.moveLocalEvents(this)
                        if (moved > 0) Log.i(TAG, "moved $moved local calendar events")
                    }
                }
            } finally {
                mainHandler.post { finish(run) }
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val run = runs[params.jobId]
        if (run == null || run.params !== params || !runs.remove(params.jobId, run)) {
            return false
        }
        run.stopped.set(true)
        return true
    }

    private fun finish(run: JobRun) {
        if (run.stopped.get() || !runs.remove(run.params.jobId, run)) return
        jobFinished(run.params, false)
        CalendarRouting.scheduleIfEnabled(this)
    }

    private class JobRun(val params: JobParameters) {
        val stopped = AtomicBoolean()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runs = ConcurrentHashMap<Int, JobRun>()

    companion object {
        private const val TAG = "CalendarRoutingJob"
        private val WORK_LOCK = Any()
    }
}
