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
        val action = StoreCloneHint.pickAction(
            mapOf(StoreCloneHint.PLAY_STORE to true),
            mapOf(StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_ABSENT),
        )
        assertEquals(StoreCloneHint.PLAY_STORE to StoreCloneHint.ACTION_CLONE, action)
    }

    @Test
    fun prefersPlayStoreOverRuStore() {
        val action = StoreCloneHint.pickAction(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            mapOf(
                StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_ABSENT,
                StoreCloneHint.RU_STORE to StoreCloneHint.WORK_STATE_ABSENT,
            ),
        )
        assertEquals(StoreCloneHint.PLAY_STORE to StoreCloneHint.ACTION_CLONE, action)
    }

    @Test
    fun fallsBackToRuStoreWhenPlayAbsent() {
        val action = StoreCloneHint.pickAction(
            mapOf(
                StoreCloneHint.PLAY_STORE to false,
                StoreCloneHint.RU_STORE to true,
            ),
            mapOf(
                StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_ABSENT,
                StoreCloneHint.RU_STORE to StoreCloneHint.WORK_STATE_ABSENT,
            ),
        )
        assertEquals(StoreCloneHint.RU_STORE to StoreCloneHint.ACTION_CLONE, action)
    }

    @Test
    fun skipsStoreAvailableInWorkProfile() {
        // C1: Play лежит в образе GMS-устройства -- вхождение в showAll-список
        // недостаточно, должно быть AVAILABLE по факту (installed + launcher).
        val action = StoreCloneHint.pickAction(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            mapOf(
                StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_AVAILABLE,
                StoreCloneHint.RU_STORE to StoreCloneHint.WORK_STATE_ABSENT,
            ),
        )
        assertEquals(StoreCloneHint.RU_STORE to StoreCloneHint.ACTION_CLONE, action)
    }

    @Test
    fun nothingToAskWhenBothStoresAvailableInWork() {
        val action = StoreCloneHint.pickAction(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            mapOf(
                StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_AVAILABLE,
                StoreCloneHint.RU_STORE to StoreCloneHint.WORK_STATE_AVAILABLE,
            ),
        )
        assertNull(action)
    }

    @Test
    fun offersUnfreezeForFrozenStore() {
        val action = StoreCloneHint.pickAction(
            mapOf(StoreCloneHint.PLAY_STORE to true),
            mapOf(StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_FROZEN),
        )
        assertEquals(StoreCloneHint.PLAY_STORE to StoreCloneHint.ACTION_UNFREEZE, action)
    }

    @Test
    fun unfreezePreferredOverCloningNextCandidate() {
        val action = StoreCloneHint.pickAction(
            mapOf(
                StoreCloneHint.PLAY_STORE to true,
                StoreCloneHint.RU_STORE to true,
            ),
            mapOf(
                StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_FROZEN,
                StoreCloneHint.RU_STORE to StoreCloneHint.WORK_STATE_ABSENT,
            ),
        )
        assertEquals(StoreCloneHint.PLAY_STORE to StoreCloneHint.ACTION_UNFREEZE, action)
    }

    @Test
    fun ignoresStoreEntryWithoutApkInMain() {
        // getInstalledApplications(MATCH_UNINSTALLED_PACKAGES) может вернуть
        // скелет без sourceDir -- такой "магазин" клонировать нельзя.
        val action = StoreCloneHint.pickAction(
            mapOf(StoreCloneHint.PLAY_STORE to false),
            mapOf(StoreCloneHint.PLAY_STORE to StoreCloneHint.WORK_STATE_ABSENT),
        )
        assertNull(action)
    }

    @Test
    fun classifiesSkeletalPackageAsAbsent() {
        // Системный пакет из образа без FLAG_INSTALLED: ни клонировать,
        // ни разморозить -- его нет (C1, именно это ломало подсказку).
        assertEquals(
            StoreCloneHint.WORK_STATE_ABSENT,
            StoreCloneHint.classifyWorkState(installed = false, hidden = false, canLaunch = false),
        )
    }

    @Test
    fun classifiesHiddenAsFrozen() {
        // Скрытый пакет: launcher-интент PM может не вернуть, но пакет
        // установлен -- правильная операция разморозка, а не клонирование.
        assertEquals(
            StoreCloneHint.WORK_STATE_FROZEN,
            StoreCloneHint.classifyWorkState(installed = true, hidden = true, canLaunch = false),
        )
    }

    @Test
    fun classifiesInstalledAndLaunchableAsAvailable() {
        assertEquals(
            StoreCloneHint.WORK_STATE_AVAILABLE,
            StoreCloneHint.classifyWorkState(installed = true, hidden = false, canLaunch = true),
        )
    }

    @Test
    fun classifiesInstalledWithoutLauncherAsAbsent() {
        // Установлен, но не запускается (сервисный/отключённый пакет) --
        // клонирование его не починит, честнее считать отсутствующим.
        assertEquals(
            StoreCloneHint.WORK_STATE_ABSENT,
            StoreCloneHint.classifyWorkState(installed = true, hidden = false, canLaunch = false),
        )
    }
}
