package io.gatekeeper

import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.AntiSpyReaction
import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.AntiSpyWatchRestart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение сторожа целиком выводится из настроек, поэтому проверяется отдельно от Android:
 * цена ошибки здесь -- погашенные без спроса уведомления мессенджера и банков
 * (`docs/antispy_settings_requirements.md`). Триггер один -- подъём туннеля; заморозка по
 * блокировке экрана переехала на экран «Заморозка» (ScreenLockFreezeScope).
 */
class AntiSpyWatchPolicyTest {
    @Test
    fun featureIsOffUntilTheUserTurnsItOn() {
        val fresh = AntiSpyWatchConfig()
        assertFalse("функция обязана быть выключена по умолчанию", fresh.enabled)
        assertFalse("сторож не поднимается на настройках по умолчанию", fresh.watcherNeeded)
        assertEquals(AntiSpyReaction.IGNORE, fresh.reaction)
    }

    @Test
    fun masterSwitchOverridesTrigger() {
        val config = AntiSpyWatchConfig(enabled = false, freezeOnVpn = true)
        assertFalse(config.watcherNeeded)
        assertEquals(AntiSpyReaction.IGNORE, config.reaction)
    }

    @Test
    fun vpnTriggerOffMeansNoReaction() {
        val config = AntiSpyWatchConfig(enabled = true, freezeOnVpn = false, delaySeconds = 0)
        assertFalse(config.watcherNeeded)
        assertEquals(AntiSpyReaction.IGNORE, config.reaction)
    }

    @Test
    fun vpnTriggerOnReacts() {
        val config = AntiSpyWatchConfig(enabled = true, freezeOnVpn = true, delaySeconds = 0)
        assertTrue("включённый триггер требует сторожа", config.watcherNeeded)
        assertEquals(AntiSpyReaction.FREEZE_NOW, config.reaction)
    }

    @Test
    fun notifyOnlyNeverFreezes() {
        val config = AntiSpyWatchConfig(
            enabled = true,
            freezeOnVpn = true,
            notifyOnly = true,
            delaySeconds = 30,
        )
        assertEquals(AntiSpyReaction.NOTIFY_ONLY, config.reaction)
    }

    @Test
    fun positiveDelayGivesTheUserAChanceToCancel() {
        val delayed = AntiSpyWatchConfig(enabled = true, freezeOnVpn = true, delaySeconds = 20)
        assertEquals(AntiSpyReaction.FREEZE_AFTER_DELAY, delayed.reaction)
        assertEquals(
            AntiSpyReaction.FREEZE_NOW,
            delayed.copy(delaySeconds = 0).reaction,
        )
    }

    @Test
    fun scopeIsStoredAndNeverGuessed() {
        assertEquals(
            AntiSpyFreezeScope.AUTO_FREEZE_LIST,
            AntiSpyFreezeScope.fromStored(Int.MIN_VALUE)
        )
        for (scope in AntiSpyFreezeScope.entries) {
            assertEquals(scope, AntiSpyFreezeScope.fromStored(scope.stored))
        }
    }

    @Test
    fun restartBackoffGrowsAndThenGivesUp() {
        val first = AntiSpyWatchRestart.delayMsForAttempt(0)
        assertTrue("первая попытка обязана быть отложенной", first != null && first >= 5_000L)
        var previous = first!!
        for (attempt in 1 until AntiSpyWatchRestart.MAX_ATTEMPTS) {
            val delay = AntiSpyWatchRestart.delayMsForAttempt(attempt)
                ?: throw AssertionError("попытка $attempt отброшена раньше предела")
            assertTrue("откат обязан расти: $previous -> $delay", delay > previous)
            previous = delay
        }
        assertNull(
            "после предела попыток перезапуск прекращается, иначе это тот же цикл",
            AntiSpyWatchRestart.delayMsForAttempt(AntiSpyWatchRestart.MAX_ATTEMPTS)
        )
    }
}
