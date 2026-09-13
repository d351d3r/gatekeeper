package io.gatekeeper.util

object PowerDiagnostics {
    enum class Status { YES, NO, UNKNOWN }

    data class ProfileSignals(
        val ignoringBatteryOptimizations: Boolean,
        val backgroundRestricted: Boolean,
    )

    data class Snapshot(
        val mainIgnoringBatteryOptimizations: Status,
        val workIgnoringBatteryOptimizations: Status,
        val mainBackgroundRestricted: Status,
        val workBackgroundRestricted: Status,
        val powerSaveMode: Status,
        val deviceIdleMode: Status,
        val workServiceAlive: Status,
    ) {
        fun hasProblem(): Boolean =
            mainIgnoringBatteryOptimizations == Status.NO ||
                workIgnoringBatteryOptimizations == Status.NO ||
                mainBackgroundRestricted == Status.YES ||
                workBackgroundRestricted == Status.YES ||
                workServiceAlive == Status.NO
    }

    fun collect(
        mainSignals: ProfileSignals?,
        workSignals: ProfileSignals?,
        powerSaveMode: Boolean?,
        deviceIdleMode: Boolean?,
        workServiceAlive: Boolean?,
    ): Snapshot = Snapshot(
        mainIgnoringBatteryOptimizations = mainSignals?.ignoringBatteryOptimizations.asStatus(),
        workIgnoringBatteryOptimizations = workSignals?.ignoringBatteryOptimizations.asStatus(),
        mainBackgroundRestricted = mainSignals?.backgroundRestricted.asStatus(),
        workBackgroundRestricted = workSignals?.backgroundRestricted.asStatus(),
        powerSaveMode = powerSaveMode.asStatus(),
        deviceIdleMode = deviceIdleMode.asStatus(),
        workServiceAlive = workServiceAlive.asStatus(),
    )

    private fun Boolean?.asStatus(): Status = when (this) {
        true -> Status.YES
        false -> Status.NO
        null -> Status.UNKNOWN
    }
}
