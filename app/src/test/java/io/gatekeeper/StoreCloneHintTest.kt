package io.gatekeeper

import io.gatekeeper.util.StoreCloneHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCloneHintTest {
    private val now = 1_000_000_000L

    @Test
    fun neverAskedIsAlwaysDue() {
        assertTrue(StoreCloneHint.isDue(StoreCloneHint.STATE_NEVER_ASKED, 0L, now))
    }

    @Test
    fun neverIsNeverDue() {
        assertFalse(StoreCloneHint.isDue(StoreCloneHint.STATE_NEVER, 0L, now))
    }

    @Test
    fun snoozedWithinWeekIsNotDue() {
        assertFalse(
            StoreCloneHint.isDue(
                StoreCloneHint.STATE_SNOOZED,
                now - StoreCloneHint.SNOOZE_MS + 1,
                now,
            )
        )
    }

    @Test
    fun snoozedAfterWeekIsDueAgain() {
        assertTrue(
            StoreCloneHint.isDue(
                StoreCloneHint.STATE_SNOOZED,
                now - StoreCloneHint.SNOOZE_MS,
                now,
            )
        )
    }

    @Test
    fun picksPlayStoreWhenMissingFromWork() {
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(StoreCloneHint.PLAY_STORE to true),
            emptySet(),
        )
        assertEquals(StoreCloneHint.PLAY_STORE, candidate)
    }

    @Test
    fun prefersPlayStoreOverRuStore() {
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            emptySet(),
        )
        assertEquals(StoreCloneHint.PLAY_STORE, candidate)
    }

    @Test
    fun fallsBackToRuStoreWhenPlayAbsent() {
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(StoreCloneHint.RU_STORE to true),
            emptySet(),
        )
        assertEquals(StoreCloneHint.RU_STORE, candidate)
    }

    @Test
    fun skipsStoreAlreadyInWorkProfile() {
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            setOf(StoreCloneHint.PLAY_STORE),
        )
        assertEquals(StoreCloneHint.RU_STORE, candidate)
    }

    @Test
    fun nothingToAskWhenBothStoresPresentInWork() {
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            setOf(StoreCloneHint.PLAY_STORE, StoreCloneHint.RU_STORE),
        )
        assertNull(candidate)
    }

    @Test
    fun ignoresStoreEntryWithoutApkInMain() {
        // getInstalledApplications(MATCH_UNINSTALLED_PACKAGES) может вернуть
        // скелет без sourceDir -- такой "магазин" клонировать нельзя.
        val candidate = StoreCloneHint.pickCandidate(
            mapOf(StoreCloneHint.PLAY_STORE to false),
            emptySet(),
        )
        assertNull(candidate)
    }
}
