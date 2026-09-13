package io.gatekeeper

import android.util.Log
import io.gatekeeper.util.Utility
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверяет, что JVM-набор собирается и исполняется на классах приложения.
 * [Utility.normalizeStringList] чистая: Android-типов не требует и годится как канарейка
 * для конфигурации `testOptions.unitTests.returnDefaultValues`.
 */
class SmokeTest {
    @Test
    fun normalizeStringList_treatsNullAndEmptyPlaceholderAsEmpty() {
        assertArrayEquals(emptyArray<String>(), Utility.normalizeStringList(null))
        assertArrayEquals(emptyArray<String>(), Utility.normalizeStringList(emptyArray()))
        assertArrayEquals(emptyArray<String>(), Utility.normalizeStringList(arrayOf("")))
    }

    @Test
    fun normalizeStringList_keepsRealEntries() {
        assertArrayEquals(
            arrayOf("com.example.a", "com.example.b"),
            Utility.normalizeStringList(arrayOf("com.example.a", "com.example.b"))
        )
        assertArrayEquals(arrayOf("", "com.example.a"), Utility.normalizeStringList(arrayOf("", "com.example.a")))
    }

    /**
     * Заглушки android.jar возвращают значение по умолчанию, а не бросают
     * `RuntimeException: Stub!`. От этого зависят JVM-тесты последующих фаз: тестируемая
     * логика пишет в лог.
     */
    @Test
    fun androidStubsReturnDefaultValues() {
        assertEquals(0, Log.i("SmokeTest", "unit tests see android.jar stubs"))
    }
}
