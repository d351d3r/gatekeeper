package io.gatekeeper

import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadTest {
    private fun samplePayload() = BackupPayload.Payload(
        settings = mapOf(
            LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER to
                BackupPayload.SettingValue(BackupPayload.TYPE_BOOLEAN, true),
            LocalStorageManager.PREF_AUTO_FREEZE_DELAY to
                BackupPayload.SettingValue(BackupPayload.TYPE_INT, 30),
        ),
        mainApps = listOf("org.mozilla.firefox", "com.android.vending"),
        workApps = listOf("org.telegram.messenger"),
        autoFreezeWork = listOf("org.telegram.messenger"),
    )

    @Test
    fun roundTripPreservesEverything() {
        val parsed = BackupPayload.parse(BackupPayload.serialize(samplePayload()))!!
        assertEquals(
            true,
            parsed.settings[LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER]!!.value,
        )
        assertEquals(
            BackupPayload.TYPE_BOOLEAN,
            parsed.settings[LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER]!!.type,
        )
        assertEquals(
            30,
            parsed.settings[LocalStorageManager.PREF_AUTO_FREEZE_DELAY]!!.value,
        )
        assertEquals(listOf("org.mozilla.firefox", "com.android.vending"), parsed.mainApps)
        assertEquals(listOf("org.telegram.messenger"), parsed.workApps)
        assertEquals(listOf("org.telegram.messenger"), parsed.autoFreezeWork)
    }

    @Test
    fun rejectsForeignFormat() {
        assertNull(BackupPayload.parse("""{"format":"other","version":1}"""))
    }

    @Test
    fun rejectsNewerVersion() {
        val json = """{"format":"gatekeeper-backup","version":99,"settings":{},"mainApps":[],"workApps":[],"autoFreezeWork":[]}"""
        assertNull(BackupPayload.parse(json))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(BackupPayload.parse("not json at all"))
        assertNull(BackupPayload.parse(""))
    }

    @Test
    fun unknownSettingTypeIsSkippedNotFatal() {
        val json = """{"format":"gatekeeper-backup","version":1,"settings":{"x":{"type":"weird","value":1}},"mainApps":[],"workApps":[],"autoFreezeWork":[]}"""
        val parsed = BackupPayload.parse(json)!!
        assertTrue(parsed.settings.isEmpty())
    }

    @Test
    fun intTypeIsNotDemotedToBooleanOrString() {
        // Регрессия: тип SharedPreferences должен пережить round-trip,
        // иначе getInt() на стороне импорта вернёт дефолт.
        val payload = samplePayload()
        val parsed = BackupPayload.parse(BackupPayload.serialize(payload))!!
        val delay = parsed.settings[LocalStorageManager.PREF_AUTO_FREEZE_DELAY]!!
        assertEquals(BackupPayload.TYPE_INT, delay.type)
        assertEquals(30, delay.value)
    }

    @Test
    fun authKeyIsNeverExportable() {
        // Секрет не должен попасть в белый список даже по ошибке.
        assertTrue(LocalStorageManager.PREF_AUTH_KEY !in BackupPayload.EXPORTABLE_SETTINGS)
        assertTrue(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED !in BackupPayload.EXPORTABLE_SETTINGS)
    }

    @Test
    fun exportableSettingsReferenceRealPrefs() {
        // Белый список не должен ссылаться на удалённые константы.
        assertTrue(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE in BackupPayload.EXPORTABLE_SETTINGS)
        assertTrue(LocalStorageManager.PREF_ANTI_SPY_VPN_WATCH_ENABLED in BackupPayload.EXPORTABLE_SETTINGS)
    }
}
