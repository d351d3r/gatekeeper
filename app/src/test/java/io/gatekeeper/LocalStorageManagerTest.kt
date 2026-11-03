package io.gatekeeper

import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Списки в настройках -- одна строка через запятую: список автозаморозки, отказы от
 * нее, правила ссылок. Ошибка в разборе молча теряет или добавляет пакет.
 */
class LocalStorageManagerTest {
    private val list = LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE
    private lateinit var storage: LocalStorageManager

    @Before
    fun setUp() {
        TestStorage.install()
        storage = LocalStorageManager.getInstance()
    }

    /** На этот маркер опираются getAutoFreezeDelay и ScreenLockFreezeScope.fromStored. */
    @Test
    fun missingIntReadsAsMinValue() {
        assertEquals(Int.MIN_VALUE, storage.getInt("missing"))
        assertFalse(storage.getBoolean("missing"))
        assertNull(storage.getString("missing"))
    }

    @Test
    fun appendContainsAndRemoveEveryCopy() {
        assertEquals(0, storage.getStringList(list).size)
        storage.appendStringList(list, "com.bank")
        storage.appendStringList(list, "com.mail")
        storage.appendStringList(list, "com.bank")
        assertArrayEquals(arrayOf("com.bank", "com.mail", "com.bank"), storage.getStringList(list))
        assertTrue(storage.stringListContains(list, "com.mail"))

        storage.removeFromStringList(list, "com.bank")
        assertArrayEquals(arrayOf("com.mail"), storage.getStringList(list))
    }

    /** Пустые элементы (",," после ручной правки, пустой список) не становятся пакетами. */
    @Test
    fun emptyEntriesAreDropped() {
        storage.setString(list, ",com.bank,,com.mail,")
        assertArrayEquals(arrayOf("com.bank", "com.mail"), storage.getStringList(list))
        storage.setStringList(list, emptyArray())
        assertEquals(0, storage.getStringList(list).size)
        assertFalse(storage.stringListContains(list, ""))
    }

    /** Вхождение -- только элемент целиком: префикс или продолжение имени пакета не считаются. */
    @Test
    fun containsMatchesWholeEntriesOnly() {
        storage.setStringList(list, arrayOf("com.bank.app"))
        assertFalse(storage.stringListContains(list, "com.bank"))
        assertFalse(storage.stringListContains(list, "com.bank.app2"))
        assertTrue(storage.stringListContains(list, "com.bank.app"))
    }

    @Test
    fun synchronousWritesAreVisibleImmediately() {
        storage.setBooleanNow("flag", true)
        storage.setIntNow("count", 3)
        storage.setStringNow("name", "v")
        assertTrue(storage.getBoolean("flag"))
        assertEquals(3, storage.getInt("count"))
        storage.removeNow("name")
        assertFalse(storage.contains("name"))
    }

    /** C4: в снимок для бэкапа попадает только белый список, типы сохраняются. */
    @Test
    fun snapshotTakesOnlyAllowlistedKeysAndKeepsTypes() {
        storage.setBoolean("b", true)
        storage.setInt("i", 5)
        storage.setLong("l", 7L)
        storage.setString("s", "v")
        storage.setString(LocalStorageManager.PREF_AUTH_KEY, "00FF")

        val snapshot = storage.snapshotSettings(setOf("b", "i", "l", "s", "missing"))
        assertEquals(setOf("b", "i", "l", "s"), snapshot.keys)
        assertEquals(BackupPayload.TYPE_BOOLEAN, snapshot.getValue("b").type)
        assertEquals(BackupPayload.TYPE_INT, snapshot.getValue("i").type)
        assertEquals(BackupPayload.TYPE_LONG, snapshot.getValue("l").type)
        assertEquals(BackupPayload.TYPE_STRING, snapshot.getValue("s").type)

        TestStorage.install()
        val restored = LocalStorageManager.getInstance()
        restored.applySettings(snapshot)
        assertTrue(restored.getBoolean("b"))
        assertEquals(5, restored.getInt("i"))
        assertEquals(7L, restored.getLong("l", 0L))
        assertEquals("v", restored.getString("s"))
        assertFalse(restored.contains(LocalStorageManager.PREF_AUTH_KEY))
    }
}
