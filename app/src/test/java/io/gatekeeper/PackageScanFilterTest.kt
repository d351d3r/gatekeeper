package io.gatekeeper

import android.content.Intent
import io.gatekeeper.util.PackageEntry
import io.gatekeeper.util.PackageScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageScanFilterTest {
    @Test
    fun keepsOnlyInstalledThirdPartyPackages() {
        val entries = listOf(
            PackageEntry("com.example.store", isSystem = false, isInstalled = true),
            PackageEntry("com.android.systemui", isSystem = true, isInstalled = true),
            PackageEntry("io.gatekeeper", isSystem = false, isInstalled = true),
            PackageEntry("com.example.removed", isSystem = false, isInstalled = false),
            PackageEntry("", isSystem = false, isInstalled = true),
        )
        val selected = PackageScanFilter.selectScannedPackages("io.gatekeeper", entries)
        assertEquals(arrayOf("com.example.store"), selected)
    }

    @Test
    fun emptySelectionForNoCandidates() {
        val selected = PackageScanFilter.selectScannedPackages(
            "io.gatekeeper",
            listOf(PackageEntry("com.example.a", isSystem = true, isInstalled = true))
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun installEventPredicate() {
        assertTrue(
            PackageScanFilter.shouldProcessInstallEvent(
                Intent.ACTION_PACKAGE_ADDED, replacing = false, self = false
            )
        )
        assertFalse(
            PackageScanFilter.shouldProcessInstallEvent(
                Intent.ACTION_PACKAGE_REMOVED, replacing = false, self = false
            )
        )
        assertFalse(
            PackageScanFilter.shouldProcessInstallEvent(
                Intent.ACTION_PACKAGE_ADDED, replacing = true, self = false
            )
        )
        assertFalse(
            PackageScanFilter.shouldProcessInstallEvent(
                Intent.ACTION_PACKAGE_ADDED, replacing = false, self = true
            )
        )
    }
}
