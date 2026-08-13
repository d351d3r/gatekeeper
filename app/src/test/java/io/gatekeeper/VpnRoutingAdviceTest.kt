package io.gatekeeper

import io.gatekeeper.util.VpnRoutingAdvice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Замер на устройстве пользователя 10.08.2026: трафик рабочего профиля идет мимо VPN,
 * поднятого в личном (`docs/threat_model.md`). В такой конфигурации автозаморозка по подъему
 * VPN не дает защиты, но гасит уведомления, и приложение обязано сказать это по факту,
 * а не по теории.
 */
class VpnRoutingAdviceTest {
    @Test
    fun warnsWhenTheProfileIsOutsideTheTunnel() {
        assertEquals(
            VpnRoutingAdvice.WORK_PROFILE_BYPASSES_TUNNEL,
            VpnRoutingAdvice.evaluate(personalTunneled = true, workTunneled = false)
        )
    }

    @Test
    fun staysQuietWhenTheProfileIsInsideTheTunnel() {
        assertEquals(
            VpnRoutingAdvice.WORK_PROFILE_IN_TUNNEL,
            VpnRoutingAdvice.evaluate(personalTunneled = true, workTunneled = true)
        )
    }

    @Test
    fun tunneledProfileOutweighsTheLocalView() {
        // Личный профиль может быть выведен из-под VPN намеренно (раздельное туннелирование).
        assertEquals(
            VpnRoutingAdvice.WORK_PROFILE_IN_TUNNEL,
            VpnRoutingAdvice.evaluate(personalTunneled = false, workTunneled = true)
        )
    }

    @Test
    fun saysNothingWhileTheTunnelIsDown() {
        assertEquals(
            VpnRoutingAdvice.VPN_DOWN,
            VpnRoutingAdvice.evaluate(personalTunneled = false, workTunneled = false)
        )
        assertEquals(
            VpnRoutingAdvice.VPN_DOWN,
            VpnRoutingAdvice.evaluate(personalTunneled = false, workTunneled = null)
        )
    }

    @Test
    fun admitsItCannotTellWithoutTheWorkProfile() {
        assertEquals(
            VpnRoutingAdvice.WORK_PROFILE_UNREACHABLE,
            VpnRoutingAdvice.evaluate(personalTunneled = true, workTunneled = null)
        )
    }
}
