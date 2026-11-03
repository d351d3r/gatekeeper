package io.gatekeeper

import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Чистые помощники Utility; остальное в нем -- обертки над системными сервисами. */
class UtilityTest {
    /** Расширение -- только у имени файла; регистр не важен для MimeTypeMap до Android 10. */
    @Test
    fun fileExtensionComesFromTheFileNameInLowerCase() {
        assertEquals("jpg", Utility.getFileExtension("/storage/emulated/10/DCIM/Camera/IMG_0001.JPG"))
        assertEquals("gz", Utility.getFileExtension("backup.tar.gz"))
        assertNull(Utility.getFileExtension("/storage/emulated/10/Download/v1.2/README"))
        assertNull(Utility.getFileExtension("/storage/emulated/10/.nomedia"))
        assertNull(Utility.getFileExtension("trailing."))
        assertNull(Utility.getFileExtension(""))
    }

    private fun bounds(width: Int, height: Int) = BitmapFactory.Options().apply {
        outWidth = width
        outHeight = height
    }

    @Test
    fun sampleSizeKeepsTheImageAtLeastAsLargeAsRequested() {
        assertEquals(1, Utility.calculateInSampleSize(bounds(800, 600), 1000, 1000))
        assertEquals(2, Utility.calculateInSampleSize(bounds(4000, 3000), 1000, 1000))
        assertEquals(4, Utility.calculateInSampleSize(bounds(4000, 3000), 500, 500))
    }

    /** Нулевой размер миниатюры раньше крутил цикл до переполнения и деления на ноль. */
    @Test
    fun nonPositiveRequestedSizeMeansNoSampling() {
        assertEquals(1, Utility.calculateInSampleSize(bounds(4000, 3000), 0, 0))
        assertEquals(1, Utility.calculateInSampleSize(bounds(4000, 3000), -1, 100))
    }

    /** Файл не картинка: декодер оставляет размеры -1. */
    @Test
    fun undecodableBoundsAreNotSampled() {
        assertEquals(1, Utility.calculateInSampleSize(bounds(-1, -1), 256, 256))
    }

    @Test
    fun pipeCopiesEverythingAcrossBufferBoundaries() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        Utility.pipe(ByteArrayInputStream(data), out)
        assertArrayEquals(data, out.toByteArray())

        val empty = ByteArrayOutputStream()
        Utility.pipe(ByteArrayInputStream(ByteArray(0)), empty)
        assertEquals(0, empty.size())
    }

    @Test
    fun emptyStoredListsNormalizeToEmpty() {
        assertEquals(0, Utility.normalizeStringList(null).size)
        assertEquals(0, Utility.normalizeStringList(emptyArray()).size)
        assertEquals(0, Utility.normalizeStringList(arrayOf("")).size)
        assertArrayEquals(arrayOf("com.bank"), Utility.normalizeStringList(arrayOf("com.bank")))
    }

    /** Удаленные из профиля пакеты уходят из списка; оставшиеся -- в прежнем порядке. */
    @Test
    fun missingAppsAreDroppedFromAStoredList() {
        TestStorage.install()
        val storage = LocalStorageManager.getInstance()
        val pref = LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE
        storage.setStringList(pref, arrayOf("com.bank", "com.gone", "com.mail"))
        val installed = listOf("com.mail", "com.bank").map { pkg ->
            ApplicationInfoWrapper(ApplicationInfo().also { it.packageName = pkg })
        }

        Utility.deleteMissingApps(pref, installed)

        assertArrayEquals(arrayOf("com.bank", "com.mail"), storage.getStringList(pref))
    }
}
