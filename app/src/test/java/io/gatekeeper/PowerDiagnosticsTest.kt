package io.gatekeeper

import io.gatekeeper.util.PowerDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerDiagnosticsTest {
    @Test
    fun collectsWithoutCrash_whenWorkServiceMissing() {
        val snapshot = PowerDiagnostics.collect(
            mainSignals = PowerDiagnostics.ProfileSignals(
                ignoringBatteryOptimizations = true,
                backgroundRestricted = false,
            ),
            workSignals = null,
            powerSaveMode = false,
            deviceIdleMode = false,
            workServiceAlive = false,
        )

        assertEquals(PowerDiagnostics.Status.YES, snapshot.mainIgnoringBatteryOptimizations)
        assertEquals(PowerDiagnostics.Status.NO, snapshot.mainBackgroundRestricted)
        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.workIgnoringBatteryOptimizations)
        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.workBackgroundRestricted)
        assertEquals(PowerDiagnostics.Status.NO, snapshot.powerSaveMode)
        assertEquals(PowerDiagnostics.Status.NO, snapshot.deviceIdleMode)
        assertEquals(PowerDiagnostics.Status.NO, snapshot.workServiceAlive)
    }

    @Test
    fun mapsUnavailableSystemSignalsToUnknown() {
        val snapshot = PowerDiagnostics.collect(
            mainSignals = null,
            workSignals = PowerDiagnostics.ProfileSignals(
                ignoringBatteryOptimizations = false,
                backgroundRestricted = true,
            ),
            powerSaveMode = null,
            deviceIdleMode = null,
            workServiceAlive = null,
        )

        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.mainIgnoringBatteryOptimizations)
        assertEquals(PowerDiagnostics.Status.NO, snapshot.workIgnoringBatteryOptimizations)
        assertEquals(PowerDiagnostics.Status.YES, snapshot.workBackgroundRestricted)
        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.powerSaveMode)
        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.deviceIdleMode)
        assertEquals(PowerDiagnostics.Status.UNKNOWN, snapshot.workServiceAlive)
    }
}
