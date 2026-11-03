package io.gatekeeper

import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ScreenLockFreezeMigration
import io.gatekeeper.util.ScreenLockFreezeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перенос включённой заморозки по блокировке из сторожа VPN в единую модель экрана
 * «Заморозка» (редизайн, шаг 5). Цена ошибки -- погашенные без спроса уведомления:
 * нельзя ни потерять включённую защиту, ни молча превратить «только уведомлять»
 * в настоящую заморозку.
 */
class ScreenLockFreezeMigrationTest {
    @Test
    fun nothingToMigrateWhenLegacyTriggerWasOff() {
        assertNull(
            ScreenLockFreezeMigration.decide(
                screenLockEnabled = false,
                notifyOnly = false,
                wholeProfileScope = false,
                legacyDelaySeconds = 15,
            )
        )
    }

    @Test
    fun notifyOnlyIsDroppedNotConverted() {
        // «Только уведомлять» у FreezeService не выжило; молча включать настоящую
        // заморозку нельзя -- уведомления приложений погаснут.
        assertNull(
            ScreenLockFreezeMigration.decide(
                screenLockEnabled = true,
                notifyOnly = true,
                wholeProfileScope = false,
                legacyDelaySeconds = 15,
            )
        )
    }

    @Test
    fun freezeScopeMapsToAutoFreezeListByDefault() {
        val decision = ScreenLockFreezeMigration.decide(
            screenLockEnabled = true,
            notifyOnly = false,
            wholeProfileScope = false,
            legacyDelaySeconds = 15,
        )
        checkNotNull(decision)
        assertEquals(true, decision.enableService)
        assertEquals(ScreenLockFreezeScope.AUTO_FREEZE_LIST, decision.scope)
    }

    @Test
    fun wholeProfileScopeSurvivesMigration() {
        val decision = ScreenLockFreezeMigration.decide(
            screenLockEnabled = true,
            notifyOnly = false,
            wholeProfileScope = true,
            legacyDelaySeconds = 30,
        )
        checkNotNull(decision)
        assertEquals(ScreenLockFreezeScope.WHOLE_WORK_PROFILE, decision.scope)
    }

    @Test
    fun knownDelaysSurviveMigration() {
        for (delay in intArrayOf(0, 60, 120, 300)) {
            val decision = ScreenLockFreezeMigration.decide(
                screenLockEnabled = true,
                notifyOnly = false,
                wholeProfileScope = false,
                legacyDelaySeconds = delay,
            )
            checkNotNull(decision)
            assertEquals(delay, decision.delaySeconds)
        }
    }

    @Test
    fun unknownDelayFallsBackToNearestChoice() {
        // Старое значение вне шкалы единой задержки (0/60/120/300) -- ближайший выбор,
        // чтобы заморозка не сработала раньше, чем пользователь привык.
        for (legacy in intArrayOf(5, 15, 30, Int.MIN_VALUE)) {
            val decision = ScreenLockFreezeMigration.decide(
                screenLockEnabled = true,
                notifyOnly = false,
                wholeProfileScope = false,
                legacyDelaySeconds = legacy,
            )
            checkNotNull(decision)
            assertEquals(60, decision.delaySeconds)
        }
    }

    @Test
    fun scopeStoredValueIsNeverGuessed() {
        assertEquals(
            ScreenLockFreezeScope.SESSION,
            ScreenLockFreezeScope.fromStored(Int.MIN_VALUE)
        )
        for (scope in ScreenLockFreezeScope.entries) {
            assertEquals(scope, ScreenLockFreezeScope.fromStored(scope.stored))
        }
    }

    /** run() читает старые настройки сторожа и отмечает перенос пройденным -- ровно один раз. */
    @Test
    fun runMigratesLegacySettingsOnce() {
        TestStorage.install()
        val storage = LocalStorageManager.getInstance()
        storage.setBoolean(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK, true)
        storage.setInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_SCOPE, AntiSpyFreezeScope.WHOLE_WORK_PROFILE.stored)
        storage.setInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY, 120)

        assertEquals(
            ScreenLockFreezeMigration.Decision(
                enableService = true,
                scope = ScreenLockFreezeScope.WHOLE_WORK_PROFILE,
                delaySeconds = 120,
            ),
            ScreenLockFreezeMigration.run(storage),
        )
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_SCREEN_LOCK_FREEZE_MIGRATED))
        assertNull(ScreenLockFreezeMigration.run(storage))
    }

    /** Без старого триггера переносить нечего, но повторно настройки не перечитываются. */
    @Test
    fun runWithoutLegacyTriggerOnlyMarksTheMigration() {
        TestStorage.install()
        val storage = LocalStorageManager.getInstance()
        assertNull(ScreenLockFreezeMigration.run(storage))
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_SCREEN_LOCK_FREEZE_MIGRATED))

        storage.setBoolean(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK, true)
        assertNull(ScreenLockFreezeMigration.run(storage))
    }
}
