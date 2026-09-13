package io.gatekeeper

import io.gatekeeper.util.NewPackagePolicy
import io.gatekeeper.util.NewPackagePolicy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3: что попадает в список автозаморозки после установки в профиль. */
class NewPackagePolicyTest {

    @Test
    fun firstRunOnlyRecordsBaseline() {
        assertTrue(NewPackagePolicy.isBaselineRun(null))
        assertFalse(NewPackagePolicy.isBaselineRun(0))
        assertFalse(NewPackagePolicy.isBaselineRun(17))
    }

    @Test
    fun ordinaryUserAppIsPicked() {
        val picked = NewPackagePolicy.pickNew(
            listOf(installed("com.example.messenger")),
            OWN,
            emptySet(),
            emptySet(),
        )
        assertEquals(listOf("com.example.messenger"), picked)
    }

    @Test
    fun systemAppsAndOwnPackageAreSkipped() {
        val picked = NewPackagePolicy.pickNew(
            listOf(
                installed(OWN),
                installed("com.android.settings").copy(system = true),
                installed("com.example.app"),
            ),
            OWN,
            emptySet(),
            emptySet(),
        )
        assertEquals(listOf("com.example.app"), picked)
    }

    /** Обновление и удаление тоже двигают номер последовательности. */
    @Test
    fun removedAndUnlaunchableAreSkipped() {
        val picked = NewPackagePolicy.pickNew(
            listOf(
                installed("com.example.gone").copy(installed = false),
                installed("com.example.headless").copy(launchable = false),
            ),
            OWN,
            emptySet(),
            emptySet(),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun alreadyListedIsNotRepeated() {
        val picked = NewPackagePolicy.pickNew(
            listOf(installed("com.example.known"), installed("com.example.new")),
            OWN,
            setOf("com.example.known"),
            emptySet(),
        )
        assertEquals(listOf("com.example.new"), picked)
    }

    /** Обновление уже знакомого приложения установкой не считается. */
    @Test
    fun knownPackageIsNotReportedAgain() {
        val picked = NewPackagePolicy.pickNew(
            listOf(installed("com.example.updated"), installed("com.example.fresh")),
            OWN,
            emptySet(),
            setOf("com.example.updated"),
        )
        assertEquals(listOf("com.example.fresh"), picked)
    }

    @Test
    fun duplicatesCollapse() {
        val picked = NewPackagePolicy.pickNew(
            listOf(installed("com.example.twice"), installed("com.example.twice")),
            OWN,
            emptySet(),
            emptySet(),
        )
        assertEquals(listOf("com.example.twice"), picked)
    }

    private fun installed(packageName: String) = Candidate(
        packageName = packageName,
        installed = true,
        system = false,
        launchable = true,
    )

    private companion object {
        const val OWN = "io.gatekeeper"
    }
}
