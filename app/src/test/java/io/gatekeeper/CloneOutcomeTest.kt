package io.gatekeeper

import io.gatekeeper.util.CloneOutcome
import io.gatekeeper.util.CloneOutcome.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Фаза 19: каждый отказ клонирования обязан расшифровываться в объяснимую причину.
 * Числа зафиксированы платформой: Activity.RESULT_OK=-1/RESULT_CANCELED=0/RESULT_FIRST_USER=1,
 * PackageInstaller.STATUS_*=0..6 (доставка через PendingIntent: resultCode = FIRST_USER + status).
 */
class CloneOutcomeTest {
    @Test
    fun successAndCancellation() {
        assertEquals(Reason.SUCCESS, CloneOutcome.reasonOf(-1))
        assertEquals(Reason.CANCELLED_BY_USER, CloneOutcome.reasonOf(0))
    }

    @Test
    fun ownGuardResults() {
        assertEquals(Reason.SYSTEM_APP_UNAVAILABLE, CloneOutcome.reasonOf(100001))
        assertEquals(Reason.ALREADY_IN_PROFILE, CloneOutcome.reasonOf(100002))
        assertEquals(Reason.NO_PROFILE_CONNECTION, CloneOutcome.reasonOf(100003))
    }

    @Test
    fun packageInstallerStatuses() {
        // resultCode = RESULT_FIRST_USER (1) + STATUS_*
        assertEquals(Reason.ABORTED, CloneOutcome.reasonOf(1 + 1))
        assertEquals(Reason.BLOCKED, CloneOutcome.reasonOf(1 + 2))
        assertEquals(Reason.CONFLICT, CloneOutcome.reasonOf(1 + 3))
        assertEquals(Reason.INCOMPATIBLE, CloneOutcome.reasonOf(1 + 4))
        assertEquals(Reason.INVALID_APK, CloneOutcome.reasonOf(1 + 5))
        assertEquals(Reason.OUT_OF_SPACE, CloneOutcome.reasonOf(1 + 6))
    }

    @Test
    fun unknownCodesStayUnknown() {
        assertEquals(Reason.UNKNOWN, CloneOutcome.reasonOf(1 + 42))
        assertEquals(Reason.UNKNOWN, CloneOutcome.reasonOf(1 + 7)) // за пределами известных статусов
        assertEquals(Reason.UNKNOWN, CloneOutcome.reasonOf(999))
        assertEquals(Reason.UNKNOWN, CloneOutcome.reasonOf(-2))
    }

    @Test
    fun everyReasonIsReachable() {
        // Гарантия полноты: enum-значение без ветки в UI -- мёртвый код или дыра в сообщениях.
        val reachable = setOf(
            -1, 0, 100001, 100002, 100003,
            1 + 1, 1 + 2, 1 + 3, 1 + 4, 1 + 5, 1 + 6, 1 + 42
        ).map(CloneOutcome::reasonOf)
        assertEquals(Reason.entries.toSet(), reachable.toSet())
    }
}
