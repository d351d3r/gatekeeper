package io.gatekeeper

import io.gatekeeper.util.WorkServiceBindFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация неуспеха привязки к сервису рабочего профиля (D3) и политика повторов.
 *
 * До правки ветки неуспеха не существовало вовсе: `bindWorkServiceCb` молча выходил, меню
 * оставалось на экране, список приложений не строился.
 */
class WorkServiceBindFailureTest {
    @Test
    fun successfulBindNeedsNoHandling() {
        assertEquals(
            WorkServiceBindFailure.NONE,
            WorkServiceBindFailure.classify(resultOk = true, hasBinder = true)
        )
    }

    @Test
    fun missingResultIsCancelled() {
        assertEquals(
            WorkServiceBindFailure.CANCELLED,
            WorkServiceBindFailure.classify(resultOk = false, hasBinder = false)
        )
        assertEquals(
            WorkServiceBindFailure.CANCELLED,
            WorkServiceBindFailure.classify(resultOk = false, hasBinder = true)
        )
    }

    /** Результат пришел, биндера в нем нет -- отдельный случай: реле дошло, сервис нет. */
    @Test
    fun resultWithoutBinderIsItsOwnReason() {
        assertEquals(
            WorkServiceBindFailure.NO_BINDER,
            WorkServiceBindFailure.classify(resultOk = true, hasBinder = false)
        )
    }

    /** R3.2: причины различимы, иначе сообщение не поможет отличить фризер от выключенного профиля. */
    @Test
    fun everyReasonHasItsOwnMessage() {
        val reasons = listOf(
            WorkServiceBindFailure.CANCELLED,
            WorkServiceBindFailure.NO_BINDER,
            WorkServiceBindFailure.NO_RESOLUTION,
        )
        val messages = reasons.map { WorkServiceBindFailure.messageOf(it) }
        assertEquals(reasons.size, messages.toSet().size)
        for (message in messages) {
            assertNotEquals(0, message)
        }
    }

    @Test
    fun successHasNoMessage() {
        assertEquals(0, WorkServiceBindFailure.messageOf(WorkServiceBindFailure.NONE))
    }

    /** R3.5: молчаливый повтор ограничен, дальше решает пользователь. */
    @Test
    fun retriesSilentlyOnceAndThenAsksTheUser() {
        assertTrue(WorkServiceBindFailure.shouldRetrySilently(0))
        assertFalse(WorkServiceBindFailure.shouldRetrySilently(WorkServiceBindFailure.SILENT_RETRIES))
        assertFalse(WorkServiceBindFailure.shouldRetrySilently(WorkServiceBindFailure.SILENT_RETRIES + 1))
    }
}
