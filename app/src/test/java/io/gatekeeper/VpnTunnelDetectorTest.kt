package io.gatekeeper

import io.gatekeeper.util.VpnTunnelDetector
import io.gatekeeper.util.VpnVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Имена туннельных интерфейсов -- первый сигнал [VpnTunnelDetector]; регекс должен держаться. */
class VpnTunnelDetectorTest {
    @Test
    fun tunnelNamesMatch() {
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("tun0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("wg0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("ppp0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("tap0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("vpn0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("utun3"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("rmnet_data0"))
        assertTrue(VpnTunnelDetector.isTunnelInterfaceName("TUN9"))
    }

    @Test
    fun regularInterfaceNamesDoNotMatch() {
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("eth0"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("wlan0"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("lo"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName(""))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("tun"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("mytun0"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("tun0x"))
        assertFalse(VpnTunnelDetector.isTunnelInterfaceName("tun 0"))
    }
}

/** Сложение трех сигналов детектора: любой «да» решает, сбой сигнала голоса не дает. */
class VpnVerdictTest {
    private fun signals(tunnel: Boolean?, transport: Boolean?, notVpnMissing: Boolean?) =
        VpnVerdict.Signals(tunnel, transport, notVpnMissing)

    @Test
    fun anySignalSeeingVpnIsEnough() {
        assertTrue(VpnVerdict.verdict(signals(true, false, false)))
        assertTrue(VpnVerdict.verdict(signals(false, true, false)))
        assertTrue(VpnVerdict.verdict(signals(false, false, true)))
    }

    @Test
    fun noSignalMeansNoVpn() {
        assertFalse(VpnVerdict.verdict(signals(false, false, false)))
    }

    /** Упавший сигнал не отменяет ответ остальных: раньше одно исключение гасило всю проверку. */
    @Test
    fun failedSignalDoesNotMaskTheOthers() {
        assertTrue(VpnVerdict.verdict(signals(null, true, null)))
        assertTrue(VpnVerdict.verdict(signals(false, null, true)))
        assertFalse(VpnVerdict.verdict(signals(null, false, false)))
    }

    /**
     * Решение: отказ всех сигналов -- «VPN нет» (fail-open). Обратное запирало бы
     * приложения со снежинкой навсегда: гейт не увидел бы VPN погашенным и после
     * ручного отключения. Тест меняется только вместе с этим решением.
     */
    @Test
    fun allSignalsFailingIsTreatedAsNoVpn() {
        assertFalse(VpnVerdict.verdict(signals(null, null, null)))
    }

    @Test
    fun probeTurnsAFailureIntoNoAnswer() {
        assertEquals(true, VpnVerdict.probe("ok") { true })
        assertEquals(false, VpnVerdict.probe("ok") { false })
        assertNull(VpnVerdict.probe("denied") { throw SecurityException("no ACCESS_NETWORK_STATE") })
        assertNull(VpnVerdict.probe("scan") { throw java.net.SocketException("netlink") })
    }
}
