package io.gatekeeper

import android.content.ComponentName
import android.content.Context
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.services.WorkPackageScanJobService
import io.gatekeeper.util.PackageEntry
import io.gatekeeper.util.PackageScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Work-profile side of the store-install auto-freeze path: the scan filter over the real
 * package manager and the persisted job registration. Freezing decisions are not asserted
 * here — they belong to the personal profile (AutoFreezeDefaults).
 */
@RunWith(AndroidJUnit4::class)
class WorkPackageScanJobServiceTest {
    private val context: Context get() = TestProfiles.targetContext

    @Test
    fun scanSelectsSelfExcludedThirdPartyPackages() {
        val pm = context.packageManager
        val entries = pm.getInstalledApplications(0).map {
            PackageEntry(
                packageName = it.packageName,
                isSystem = it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                isInstalled = true,
            )
        }
        val selected = PackageScanFilter.selectScannedPackages(context.packageName, entries)
        assertNotNull(selected)
        assertTrue("self must be excluded", context.packageName !in selected)
        assertTrue(
            "system packages must be excluded",
            selected.all { pkg ->
                val flags = pm.getApplicationInfo(pkg, 0).flags
                flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
            }
        )
    }

    @Test
    fun jobIsScheduledPersisted() {
        WorkPackageScanJobService.ensureScheduled(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.getPendingJob(0xE49F0)
        assertNotNull("periodic scan job must be registered", job)
        assertTrue(job!!.isPersisted)
        assertEquals(15L * 60_000L, job.intervalMillis)
        assertTrue(context.getSystemService(JobScheduler::class.java)!!
            .allPendingJobs.any { it.id == 0xE49F0 })
        // Idempotency: second call must not throw or duplicate.
        WorkPackageScanJobService.ensureScheduled(context)
        assertEquals(
            1,
            scheduler.allPendingJobs.count { it.id == 0xE49F0 }
        )
    }

    @Test
    fun serviceComponentResolves() {
        val cn = ComponentName(context, WorkPackageScanJobService::class.java)
        assertNotNull(pm().resolveService(
            android.content.Intent().setComponent(cn),
            0
        ))
    }

    private fun pm() = context.packageManager
}
