package io.gatekeeper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileShuttlePathResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Реалистичный DUMMY_ROOT оканчивается на "/" (см. CrossProfileDocumentsProvider),
    // поэтому "/shuttleevil" не считается его префиксом.
    private val dummyRoot = "/shuttle/"

    private fun root(): File = tmp.newFolder("storage")

    @Test
    fun dummyRootRelativePathResolvesUnderRoot() {
        val root = root()
        val resolved = FileShuttlePathResolver.resolve("$dummyRoot/DCIM", dummyRoot, root)
        assertEquals(File(root, "DCIM").canonicalPath, resolved)
    }

    @Test
    fun absolutePathInsideRootResolves() {
        val root = root()
        val inside = File(root, "Docs/report.txt")
        assertEquals(inside.canonicalPath, FileShuttlePathResolver.resolve(inside.path, dummyRoot, root))
    }

    @Test
    fun rootItselfResolves() {
        val root = root()
        assertEquals(root.canonicalPath, FileShuttlePathResolver.resolve(dummyRoot, dummyRoot, root))
    }

    @Test
    fun relativePathIsRefused() {
        // E-7: раньше относительный путь молча клампился в корень.
        val root = root()
        assertNull(FileShuttlePathResolver.resolve("DCIM/Camera", dummyRoot, root))
    }

    @Test
    fun emptyPathIsRefused() {
        assertNull(FileShuttlePathResolver.resolve("", dummyRoot, root()))
    }

    @Test
    fun parentEscapeIsRefused() {
        val root = root()
        assertNull(FileShuttlePathResolver.resolve("$dummyRoot/../outside", dummyRoot, root))
    }

    @Test
    fun absolutePathOutsideRootIsRefused() {
        val root = root()
        val outside = tmp.newFolder("elsewhere")
        assertNull(FileShuttlePathResolver.resolve(outside.path, dummyRoot, root))
    }

    @Test
    fun symlinkEscapeIsRefused() {
        val root = root()
        val outside = tmp.newFolder("outside")
        val link = File(root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(FileShuttlePathResolver.resolve("$dummyRoot/link/steal.txt", dummyRoot, root))
    }

    @Test
    fun dummyRootPrefixOfLongerNameIsNotMisread() {
        // "/shuttleevil" без слэша после префикса -- не DUMMY_ROOT-путь: абсолютный
        // путь вне корня, обязан быть отклонён.
        val root = root()
        assertNull(FileShuttlePathResolver.resolve("/shuttleevil/x", dummyRoot, root))
    }
}
