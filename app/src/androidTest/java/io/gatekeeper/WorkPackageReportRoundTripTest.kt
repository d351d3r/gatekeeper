package io.gatekeeper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.receivers.WorkPackageReportReceiver
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.Utility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signature gate of the work->main package report transport. The receiver must accept a
 * properly signed fresh report and reject tampered or stale ones without side effects.
 */
@RunWith(AndroidJUnit4::class)
class WorkPackageReportRoundTripTest {
    private val context: Context get() = TestProfiles.targetContext

    private fun reportIntent(packages: Array<String>): Intent =
        Intent(Utility.ACTION_WORK_PACKAGES_REPORT).apply {
            setPackage(context.packageName)
            component = ComponentName(context, WorkPackageReportReceiver::class.java)
            putExtra("work_packages", packages)
        }

    @Test
    fun signedFreshReportIsAccepted() {
        val intent = reportIntent(arrayOf("com.example.a", "com.example.b"))
        AuthenticationUtility.signIntent(intent)
        assertTrue(AuthenticationUtility.checkIntent(intent))
    }

    @Test
    fun tamperedPackagesAreRejected() {
        val intent = reportIntent(arrayOf("com.example.a"))
        AuthenticationUtility.signIntent(intent)
        intent.putExtra("work_packages", arrayOf("com.example.evil"))
        assertFalse(AuthenticationUtility.checkIntent(intent))
    }

    @Test
    fun unsignedReportIsRejected() {
        assertFalse(AuthenticationUtility.checkIntent(reportIntent(arrayOf("com.example.a"))))
    }
}
