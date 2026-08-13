package io.gatekeeper

import android.content.Intent
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.ProfileActions
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
    fun legacyActionStillHasSystemForwarderDuringMigration() {
        val candidates = context.packageManager
            .queryIntentActivities(Intent(ProfileActions.LEGACY_START_SERVICE), 0)
            .map { it.activityInfo.packageName }
        assertTrue(
            "старый action больше не пересекает профиль: $candidates",
            candidates.contains("android")
        )
    }

    /** Действие, которого нет ни у форвардера, ни у нас, резолва не имеет вовсе. */
    @Test
    fun unknownActionIsRejected() {
        val intent = Intent("net.typeblog.shelter.action.NO_SUCH_ACTION_" + System.nanoTime())
        try {
            Utility.transferIntentToProfileUnsigned(context, intent)
            throw AssertionError("резолв не должен был найтись: ${intent.component}")
        } catch (_: IllegalStateException) {
        }
    }
}
