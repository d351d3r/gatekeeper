package io.gatekeeper.util

import android.content.Intent

/**
 * Pure (JVM-testable) filtering for work-profile package scans and install events.
 * The work-profile side only reports; freezing decisions stay on the personal side
 * (see AutoFreezeDefaults).
 */
data class PackageEntry(val packageName: String, val isSystem: Boolean, val isInstalled: Boolean)

object PackageScanFilter {
    fun selectScannedPackages(self: String, entries: List<PackageEntry>): Array<String> =
        entries.asSequence()
            .filter { it.isInstalled }
            .filter { !it.isSystem }
            .filter { it.packageName != self }
            .filter { it.packageName.isNotEmpty() }
            .map { it.packageName }
            .toList()
            .toTypedArray()

    fun shouldProcessInstallEvent(action: String?, replacing: Boolean, self: Boolean): Boolean =
        action == Intent.ACTION_PACKAGE_ADDED && !replacing && !self
}
