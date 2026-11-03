package io.gatekeeper

import android.app.admin.DevicePolicyManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.util.ImpostorScan
import io.gatekeeper.util.InstallRestrictionLease
import io.gatekeeper.util.IsolationEnforcer
import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S1 и F1 на устройстве: поиск подделок реле и аренда ограничений установки. */
@RunWith(AndroidJUnit4::class)
class ProfileGuardsTest {
    private val context = TestProfiles.targetContext

    /**
     * Тестовый APK объявляет START_SERVICE (ImpostorRelayActivity) и играет подделку.
     * Без queries для служебных действий Android 11+ скрыл бы его от поиска.
     */
    @Test
    fun testApkDeclaringARelayActionIsReported() {
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val found = ImpostorScan.findInThisProfile(context).map(ImpostorScan::decodePackage)
        assertTrue("$testPackage не найден среди $found", testPackage in found)
        assertFalse(context.packageName in found)
    }

    /**
     * F1: ограничение, включенное пользователем, снимается на время установки клона и
     * возвращается сразу после нее. Только владелец профиля, в рабочем профиле.
     */
    @Test
    fun installRestrictionIsLiftedForTheOperationAndRestored() {
        assumeTrue("нужен прогон в рабочем профиле", TestProfiles.runningInWorkProfile)
        val storage = LocalStorageManager.getInstance()
        val pref = IsolationPolicies.prefName(UserManager.DISALLOW_INSTALL_APPS)
        val previous = storage.getBoolean(pref)
        val users = context.getSystemService(UserManager::class.java)
        try {
            storage.setBooleanNow(pref, true)
            IsolationEnforcer.apply(context)
            assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))

            InstallRestrictionLease.lift(context, InstallRestrictionLease.INSTALL)
            assertFalse(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
            assertNull("отпечаток политики не сброшен", storage.getString("work_policy_fingerprint"))

            InstallRestrictionLease.restore(context)
            assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
        } finally {
            storage.setBooleanNow(pref, previous)
            IsolationEnforcer.apply(context)
        }
    }

    /** В личном профиле владельца нет -- аренда ничего не трогает и не падает. */
    @Test
    fun leaseOutsideTheWorkProfileIsANoOp() {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        assumeTrue("прогон в рабочем профиле", !dpm.isProfileOwnerApp(context.packageName))
        InstallRestrictionLease.lift(context, InstallRestrictionLease.INSTALL + InstallRestrictionLease.UNINSTALL)
        InstallRestrictionLease.restore(context)
    }
}
