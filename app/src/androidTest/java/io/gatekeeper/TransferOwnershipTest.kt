package io.gatekeeper

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F1: можно ли передать владение рабочим профилем другому приложению, не теряя
 * ни клоны, ни их состояние. Тест исполняется ВНУТРИ рабочего профиля
 * (`am instrument --user <work>`), поэтому вызов идёт от лица владельца профиля.
 *
 * Сценарий односторонний по своей природе: после передачи Gatekeeper больше не
 * владелец и вернуть владение сам не может -- обратный ход делает
 * [TransferBackReceiver] от лица нового владельца, его дёргают бродкастом
 * снаружи. Поэтому тест отмечен как ручной и не входит в обычный прогон.
 */
@RunWith(AndroidJUnit4::class)
class TransferOwnershipTest {

    @Test
    fun ownershipTransfersToCompanionAndProfileSurvives() {
        val context = TestProfiles.targetContext
        val dpm = context.getSystemService(DevicePolicyManager::class.java)!!
        assumeTrue("тест только внутри рабочего профиля", dpm.isProfileOwnerApp(context.packageName))
        assumeTrue(
            "ручной сценарий F1: запускать с -e gk.transfer 1",
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("gk.transfer") == "1",
        )

        val pm = context.packageManager
        val before = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .toSet()
        val hiddenBefore = before.filter { pkg ->
            runCatching { dpm.isApplicationHidden(adminOf(context), pkg) }.getOrDefault(false)
        }.toSet()
        Log.i(TAG, "до передачи: пакетов=${before.size}, заморожено=${hiddenBefore.size} $hiddenBefore")

        val target = ComponentName(
            "io.gatekeeper.test",
            "io.gatekeeper.TransferTargetAdminReceiver",
        )
        val bundle = PersistableBundle().apply { putString("marker", "f1-forward") }

        dpm.transferOwnership(adminOf(context), target, bundle)

        assertFalse(
            "после передачи Gatekeeper не должен быть владельцем",
            dpm.isProfileOwnerApp(context.packageName),
        )

        val after = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .toSet()
        Log.i(TAG, "после передачи: пакетов=${after.size}")
        assertTrue(
            "пакеты профиля обязаны пережить передачу: пропали ${before - after}",
            before.all { after.contains(it) },
        )
        for (pkg in hiddenBefore) {
            val visible = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
            Log.i(TAG, "замороженный $pkg после передачи виден обычным запросом: $visible")
            assertFalse("заморозка $pkg обязана пережить передачу", visible)
        }
    }

    private fun adminOf(context: android.content.Context) =
        ComponentName(context, GatekeeperDeviceAdminReceiver::class.java)

    private companion object {
        const val TAG = "TransferOwnershipTest"
    }
}
