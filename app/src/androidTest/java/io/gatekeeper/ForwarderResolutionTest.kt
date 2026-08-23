package io.gatekeeper

import android.content.Intent
import android.os.SystemClock
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.Utility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D4a на живой системе: кросс-профильное реле обязано уходить в системный форвардер, даже
 * когда те же строки действий объявляет чужое приложение. Роль чужого приложения играет сам
 * тестовый APK -- [ImpostorRelayActivity].
 */
class ForwarderResolutionTest {
    private val context get() = TestProfiles.targetContext

    @Before
    fun setUp() {
        TestProfiles.assumeRunningInPersonalProfileWithWorkProfile()
    }

    /** Без этого остальные проверки ничего не значат: сцены с перехватом просто нет. */
    @Test
    fun impostorIsVisibleAsACandidate() {
        val candidates = context.packageManager
            .queryIntentActivities(Intent(DummyActivity.START_SERVICE), 0)
            .map { it.activityInfo.packageName }
        assertTrue(
            "тестовый APK не виден приложению как кандидат резолва: $candidates",
            candidates.contains(ImpostorRelayActivity.PACKAGE_NAME)
        )
        assertTrue(
            "системного форвардера нет среди кандидатов: $candidates",
            candidates.contains("android")
        )
    }

    @Test
    fun relayGoesToTheSystemForwarder() {
        val intent = Intent(DummyActivity.START_SERVICE)
        Utility.transferIntentToProfileUnsigned(context, intent)
        assertEquals(
            "реле ушло не в системный форвардер: ${intent.component}",
            "android",
            intent.component?.packageName
        )
    }

    @Test
    fun powerSettingsRelayGoesToTheSystemForwarder() {
        refreshWorkProfilePolicies()
        val intent = Intent(DummyActivity.OPEN_POWER_SETTINGS)
        Utility.transferIntentToProfileUnsigned(context, intent)
        assertEquals(
            "настроики батареи не ушли в рабочии профиль: ${intent.component}",
            "android",
            intent.component?.packageName
        )
    }

    /** An APK update cannot alter profile-owner policy until its work-profile process runs. */
    private fun refreshWorkProfilePolicies() {
        val refresh = Intent(DummyActivity.TRY_START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Utility.transferIntentToProfileUnsigned(context, refresh)
        context.startActivity(refresh)
        repeat(20) {
            val hasSystemForwarder = context.packageManager
                .queryIntentActivities(Intent(DummyActivity.OPEN_POWER_SETTINGS), 0)
                .any { it.activityInfo.packageName == "android" }
            if (hasSystemForwarder) return
            SystemClock.sleep(100)
        }
        throw AssertionError("обновленные политики рабочего профиля не применились")
    }

    /** Действие, которого нет ни у форвардера, ни у нас, резолва не имеет вовсе. */
    @Test
    fun unknownActionIsRejected() {
        val intent = Intent("io.gatekeeper.action.NO_SUCH_ACTION_" + System.nanoTime())
        try {
            Utility.transferIntentToProfileUnsigned(context, intent)
            throw AssertionError("резолв не должен был найтись: ${intent.component}")
        } catch (_: IllegalStateException) {
        }
    }
}
