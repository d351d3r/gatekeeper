package io.gatekeeper

import io.gatekeeper.util.AntiSpyWatchConfig
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ScreenLockFreezeScope
import io.gatekeeper.util.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Что получает свежая установка. Каждая дырка в изоляции и каждая фоновая работа
 * выключены, пока пользователь их не включил.
 */
class SettingsDefaultsTest {
    private lateinit var settings: SettingsManager

    @Before
    fun setUp() {
        TestStorage.install()
        settings = SettingsManager.getInstance()
    }

    @Test
    fun crossProfileOpeningsAreOffByDefault() {
        assertFalse(settings.getShareWorkToPersonalEnabled())
        assertFalse(settings.getOpenLinksInWorkEnabled())
        assertFalse(settings.getCrossProfileContentAccessEnabled())
        assertFalse(settings.getCrossProfileInteractionAllEnabled())
        assertFalse(settings.getCreatePersonalCalendarEventsEnabled())
        assertFalse(settings.getCrossProfileFileChooserEnabled())
    }

    /** Сторож VPN -- фоновая работа в отдельном процессе: только по явному включению. */
    @Test
    fun vpnWatchIsOffByDefault() {
        val config = settings.getAntiSpyWatchConfig()
        assertFalse(config.enabled)
        assertEquals(AntiSpyWatchConfig.DEFAULT_FREEZE_ON_VPN, config.freezeOnVpn)
    }

    /** Незаданная задержка -- маркер MIN_VALUE, а не «заморозить через −2 млрд секунд». */
    @Test
    fun autoFreezeStartsImmediateAndSessionScoped() {
        assertFalse(settings.getAutoFreezeServiceEnabled())
        assertEquals(0, settings.getAutoFreezeDelay())
        assertEquals(ScreenLockFreezeScope.SESSION, settings.getAutoFreezeScope())
        assertTrue(
            LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_SCREEN_LOCK_FREEZE_MIGRATED)
        )
    }

    /** Пресет изоляции выбирается в мастере; до выбора ни одна политика не включена. */
    @Test
    fun noIsolationPolicyIsOnBeforeThePresetIsChosen() {
        val state = settings.getIsolationState()
        assertTrue(state.isNotEmpty())
        assertTrue(state.entries.joinToString { it.key }, state.values.none { it })
    }
}
