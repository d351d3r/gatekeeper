package io.gatekeeper

import io.gatekeeper.util.ImpostorScan
import io.gatekeeper.util.InstallRestrictionLease
import io.gatekeeper.util.StartActivityProxyPolicy
import io.gatekeeper.util.ProfileActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S1: поиск подделок, белый список прокси запуска и аренда ограничений установки (F1). */
class ImpostorScanTest {
    private val own = "io.gatekeeper"

    @Test
    fun ownAndSystemHandlersAreNotImpostors() {
        val handlers = listOf(own, "android", "com.evil.relay", own, "com.evil.relay", "com.other")
        assertEquals(listOf("com.evil.relay", "com.other"), ImpostorScan.foreignPackages(handlers, own))
    }

    @Test
    fun noForeignHandlersMeansNoWarning() {
        assertTrue(ImpostorScan.foreignPackages(listOf(own, "android"), own).isEmpty())
    }

    /** Имя приложения может содержать разделитель -- пакет его содержать не может. */
    @Test
    fun entryRoundTripsWithSeparatorInLabel() {
        val entry = ImpostorScan.encode("com.evil.relay", "Gatekeeper | Pro")
        assertEquals("com.evil.relay", ImpostorScan.decodePackage(entry))
        assertEquals("Gatekeeper | Pro", ImpostorScan.decodeLabel(entry))
    }

    /**
     * Каждое служебное действие DummyActivity должно и проверяться поиском подделок,
     * и быть объявлено в queries: иначе на Android 11+ подделка скрыта видимостью пакетов.
     */
    @Test
    fun everyRelayActionIsScannedAndDeclaredInQueries() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val dummy = manifest.substringAfter("android:name=\".ui.DummyActivity\"").substringBefore("</activity>")
        val relayActions = Regex("io\\.gatekeeper\\.action\\.[A-Z0-9_]+").findAll(dummy).map { it.value }.toSet()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(relayActions.isNotEmpty())
        for (action in relayActions) {
            assertTrue("$action не проверяется ImpostorScan", action in ImpostorScan.RELAY_ACTIONS)
            assertTrue("$action не объявлен в queries", queries.contains("\"$action\""))
        }
    }

    /** Статус установщика -- только через неэкспортированный псевдоним (S4). */
    @Test
    fun installerCallbackIsNotPublic() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val dummy = manifest.substringAfter("android:name=\".ui.DummyActivity\"").substringBefore("</activity>")
        assertFalse(dummy.contains(ProfileActions.PACKAGEINSTALLER_CALLBACK))
        val alias = manifest.substringAfter("android:name=\".ui.PackageInstallerCallbackAlias\"").substringBefore("/>")
        assertTrue(alias.contains("android:exported=\"false\""))
        assertTrue(alias.contains("android:targetActivity=\".ui.DummyActivity\""))
    }

    @Test
    fun proxyStartsOnlyInstallAndUninstall() {
        assertTrue(StartActivityProxyPolicy.isAllowed(ProfileActions.INSTALL_PACKAGE))
        assertTrue(StartActivityProxyPolicy.isAllowed(ProfileActions.UNINSTALL_PACKAGE))
        assertFalse(StartActivityProxyPolicy.isAllowed(ProfileActions.START_FILE_SHUTTLE))
        assertFalse(StartActivityProxyPolicy.isAllowed(ProfileActions.WIPE_PROFILE))
        assertFalse(StartActivityProxyPolicy.isAllowed(null))
    }

    @Test
    fun proxyDropsDangerousGrantFlags() {
        val newTask = 0x10000000 // FLAG_ACTIVITY_NEW_TASK
        val read = 0x1 // FLAG_GRANT_READ_URI_PERMISSION
        val write = 0x2 // FLAG_GRANT_WRITE_URI_PERMISSION
        val persistable = 0x40 // FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val prefix = 0x80 // FLAG_GRANT_PREFIX_URI_PERMISSION
        val all = newTask or read or write or persistable or prefix
        assertEquals(newTask or read, StartActivityProxyPolicy.sanitizeFlags(ProfileActions.INSTALL_PACKAGE, all))
        assertEquals(newTask, StartActivityProxyPolicy.sanitizeFlags(ProfileActions.UNINSTALL_PACKAGE, all))
    }

    @Test
    fun leaseLiftsOnlyRestrictionsTheUserEnabled() {
        val enabled = setOf("no_install_unknown_sources")
        assertEquals(
            listOf("no_install_unknown_sources"),
            InstallRestrictionLease.keysToLift(InstallRestrictionLease.INSTALL) { it in enabled },
        )
        assertTrue(InstallRestrictionLease.keysToLift(InstallRestrictionLease.UNINSTALL) { it in enabled }.isEmpty())
    }

    /** Ключи аренды совпадают с ключами политик изоляции: снимается ровно то, что включено там. */
    @Test
    fun leaseKeysAreIsolationPolicies() {
        val isolationKeys = io.gatekeeper.util.IsolationPolicies.ALL.map { it.key }.toSet()
        for (key in InstallRestrictionLease.INSTALL + InstallRestrictionLease.UNINSTALL) {
            assertTrue("$key не политика изоляции", key in isolationKeys)
        }
    }
}
