package io.gatekeeper

import io.gatekeeper.util.AntiSpyDummyVpnDisconnector
import io.gatekeeper.util.AntiSpyLaunchGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Запуск через VPN-гейт: что делать с итогом попытки погасить чужой VPN. */
class AntiSpyLaunchGateTest {
    @Test
    fun clearedVpnLetsTheLaunchProceed() {
        assertNull(AntiSpyLaunchGate.blockReasonFor(AntiSpyDummyVpnDisconnector.RESULT_CLEARED))
    }

    @Test
    fun everyOtherResultBlocksWithItsReason() {
        assertEquals(
            AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED,
            AntiSpyLaunchGate.blockReasonFor(AntiSpyDummyVpnDisconnector.RESULT_VPN_PERMISSION_REQUIRED),
        )
        assertEquals(
            AntiSpyLaunchGate.REASON_VPN_STILL_ACTIVE,
            AntiSpyLaunchGate.blockReasonFor(AntiSpyDummyVpnDisconnector.RESULT_VPN_STILL_ACTIVE),
        )
        assertEquals(
            AntiSpyLaunchGate.REASON_FAILED,
            AntiSpyLaunchGate.blockReasonFor(AntiSpyDummyVpnDisconnector.RESULT_FAILED),
        )
    }

    /** Неизвестный итог -- блок, а не запуск: при сбое приложение со снежинкой не открывается через VPN. */
    @Test
    fun unknownResultFailsClosed() {
        assertEquals(AntiSpyLaunchGate.REASON_FAILED, AntiSpyLaunchGate.blockReasonFor(42))
        assertEquals(AntiSpyLaunchGate.REASON_FAILED, AntiSpyLaunchGate.blockReasonFor(-1))
    }

    /** Про разрешение на VPN пользователю говорит вызывающий диалогом -- без тоста поверх. */
    @Test
    fun onlyThePermissionPromptIsLeftToTheCaller() {
        assertFalse(AntiSpyLaunchGate.notifiesUser(AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED))
        assertTrue(AntiSpyLaunchGate.notifiesUser(AntiSpyLaunchGate.REASON_VPN_STILL_ACTIVE))
        assertTrue(AntiSpyLaunchGate.notifiesUser(AntiSpyLaunchGate.REASON_FAILED))
    }
}
