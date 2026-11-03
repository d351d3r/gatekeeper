package io.gatekeeper

import io.gatekeeper.util.AntiSpyFreezeScope
import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ScreenLockFreezeScope
import io.gatekeeper.util.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Сеттер пишет настройку локально и отправляет то же значение в соседний профиль.
 * Недоехавшая синхронизация разводит профили молча: тумблер включен, а в другом
 * профиле ничего не изменилось. Доставку и прием письма проверяет RelayGateTest.
 */
class SettingsSyncTest {
    private lateinit var storage: LocalStorageManager
    private lateinit var settings: SettingsManager
    private val calls = ArrayList<String>()

    private val recorder = object : SettingsManager.Effects {
        override fun syncBoolean(name: String, value: Boolean) {
            calls += "bool:$name=$value"
        }

        override fun syncInt(name: String, value: Int) {
            calls += "int:$name=$value"
        }

        override fun syncIsolation(enabledKeys: Array<String>) {
            calls += "isolation:" + enabledKeys.sorted().joinToString(",")
        }

        override fun syncFreezeService() {
            calls += "freeze-service"
        }

        override fun syncVpnWatch() {
            calls += "vpn-watch"
        }

        override fun setComponentEnabled(component: Class<*>, enabled: Boolean) {
            calls += "component:${component.simpleName}=$enabled"
        }
    }

    @Before
    fun setUp() {
        TestStorage.install()
        storage = LocalStorageManager.getInstance()
        settings = SettingsManager.getInstance()
        settings.effects = recorder
    }

    @Test
    fun booleanSettersWriteLocallyAndSyncTheSameValue() {
        val setters: List<Pair<String, (Boolean) -> Unit>> = listOf(
            LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER to settings::setCrossProfileFileChooserEnabled,
            LocalStorageManager.PREF_CROSS_PROFILE_CONTENT_ACCESS to settings::setCrossProfileContentAccessEnabled,
            LocalStorageManager.PREF_CREATE_PERSONAL_CALENDAR_EVENTS to
                settings::setCreatePersonalCalendarEventsEnabled,
            LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_ALL to settings::setCrossProfileInteractionAllEnabled,
            LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING to settings::setBlockContactsSearchingEnabled,
            LocalStorageManager.PREF_BLOCK_CALLER_ID to settings::setBlockCallerIdEnabled,
            LocalStorageManager.PREF_SHARE_WORK_TO_PERSONAL to settings::setShareWorkToPersonalEnabled,
            LocalStorageManager.PREF_OPEN_LINKS_IN_WORK to settings::setOpenLinksInWorkEnabled,
            LocalStorageManager.PREF_AUTO_FREEZE_SERVICE to settings::setAutoFreezeServiceEnabled,
            LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND to settings::setSkipForegroundEnabled,
            LocalStorageManager.PREF_ANTI_SPY_VPN_WATCH_ENABLED to settings::setAntiSpyWatchEnabled,
            LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_VPN to settings::setAntiSpyFreezeOnVpn,
            LocalStorageManager.PREF_ANTI_SPY_NOTIFY_ONLY to settings::setAntiSpyNotifyOnly,
        )
        for ((pref, setter) in setters) {
            for (value in listOf(true, false)) {
                calls.clear()
                setter(value)
                assertEquals(pref, value, storage.getBoolean(pref))
                assertTrue("$pref не отправлен в профиль: $calls", "bool:$pref=$value" in calls)
            }
        }
    }

    @Test
    fun intSettersWriteLocallyAndSyncTheSameValue() {
        settings.setAutoFreezeDelay(120)
        settings.setAutoFreezeScope(ScreenLockFreezeScope.WHOLE_WORK_PROFILE)
        settings.setAntiSpyFreezeScope(AntiSpyFreezeScope.WHOLE_WORK_PROFILE)
        settings.setAntiSpyFreezeDelay(30)

        val expected = listOf(
            LocalStorageManager.PREF_AUTO_FREEZE_DELAY to 120,
            LocalStorageManager.PREF_AUTO_FREEZE_SCOPE to ScreenLockFreezeScope.WHOLE_WORK_PROFILE.stored,
            LocalStorageManager.PREF_ANTI_SPY_FREEZE_SCOPE to AntiSpyFreezeScope.WHOLE_WORK_PROFILE.stored,
            LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY to 30,
        )
        for ((pref, value) in expected) {
            assertEquals(pref, value, storage.getInt(pref))
            assertTrue("$pref не отправлен в профиль: $calls", "int:$pref=$value" in calls)
        }
    }

    /** Заморозку по блокировке держит FreezeService рабочего профиля: ему -- отдельное письмо. */
    @Test
    fun freezeServiceSettingsWakeTheWorkProfileService() {
        settings.setAutoFreezeServiceEnabled(true)
        assertEquals(1, calls.count { it == "freeze-service" })
        settings.setAutoFreezeScope(ScreenLockFreezeScope.AUTO_FREEZE_LIST)
        assertEquals(2, calls.count { it == "freeze-service" })
    }

    /** Сторож VPN живет в своем процессе: после каждой его настройки -- сверка состояния. */
    @Test
    fun vpnWatchSettingsResyncTheWatcher() {
        settings.setAntiSpyWatchEnabled(true)
        settings.setAntiSpyFreezeDelay(60)
        assertEquals(2, calls.count { it == "vpn-watch" })
    }

    /** Политики изоляции уезжают одним письмом и всем набором, а не одной политикой. */
    @Test
    fun isolationSyncCarriesTheWholeSet() {
        settings.setIsolationPolicy("no_printing", true)
        settings.setIsolationPolicy("no_autofill", true)
        assertEquals("isolation:no_autofill,no_printing", calls.last())

        settings.applyIsolationPreset(isolated = true)
        val presetKeys = IsolationPolicies.preset(true).filterValues { it }.keys.sorted()
        assertEquals("isolation:" + presetKeys.joinToString(","), calls.last())
    }

    @Test
    fun componentsFollowTheirSettings() {
        settings.setCrossProfileFileChooserEnabled(true)
        assertTrue(calls.toString(), "component:CrossProfileDocumentsProvider=true" in calls)
        settings.setPaymentStubEnabled(true)
        assertTrue(calls.toString(), "component:PaymentStubService=true" in calls)

        calls.clear()
        storage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, false)
        settings.applyAll()
        assertEquals(
            listOf("component:CrossProfileDocumentsProvider=true", "component:PaymentStubService=false"),
            calls,
        )
    }

    /** Итог разовой миграции заморозки по блокировке доезжает и до рабочего профиля. */
    @Test
    fun migratedScreenLockSettingsReachTheOtherProfile() {
        storage.setBoolean(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK, true)
        storage.setInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY, 300)

        assertEquals(ScreenLockFreezeScope.AUTO_FREEZE_LIST, settings.getAutoFreezeScope())
        assertTrue(calls.toString(), "bool:${LocalStorageManager.PREF_AUTO_FREEZE_SERVICE}=true" in calls)
        assertTrue(calls.toString(), "int:${LocalStorageManager.PREF_AUTO_FREEZE_DELAY}=300" in calls)
        val scope = ScreenLockFreezeScope.AUTO_FREEZE_LIST.stored
        assertTrue(calls.toString(), "int:${LocalStorageManager.PREF_AUTO_FREEZE_SCOPE}=$scope" in calls)
    }
}
