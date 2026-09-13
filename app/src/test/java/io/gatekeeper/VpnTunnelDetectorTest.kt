package io.gatekeeper

import io.gatekeeper.util.VpnTunnelDetector
import org.junit.Assert.assertFalse
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
