package io.gatekeeper

import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.PolicyFingerprint
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.RELAY_ACTION_FILTERS
import io.gatekeeper.util.RelayAuthorization
import io.gatekeeper.util.TO_PARENT
import io.gatekeeper.util.TO_WORK
import io.gatekeeper.util.WorkProfilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Таблица служебных кросс-профильных фильтров (A4, E-9, S2). */
class RelayFilterTableTest {
    private val dummyActions: Set<String> by lazy {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val dummy = manifest.substringAfter("android:name=\".ui.DummyActivity\"").substringBefore("</activity>")
        Regex("io\\.gatekeeper\\.action\\.[A-Z0-9_]+").findAll(dummy).map { it.value }.toSet()
    }

    private fun actionsTo(direction: Int) =
        RELAY_ACTION_FILTERS.filter { it.second == direction }.map { it.first }.toSet()

    @Test
    fun noEntryIsRegisteredTwice() {
        assertEquals(RELAY_ACTION_FILTERS.size, RELAY_ACTION_FILTERS.toSet().size)
    }

    /**
     * E-9: действие DummyActivity в обе стороны резолвится в двух целях сразу (своя
     * DummyActivity и форвардер обратно), и вместо реле система показывает выбор
     * «Personal | Work».
     */
    @Test
    fun everyDummyActivityActionHasOneDirection() {
        val relayed = RELAY_ACTION_FILTERS.map { it.first }.filter { it in dummyActions }
        assertTrue(relayed.isNotEmpty())
        for (action in relayed.toSet()) {
            assertEquals("$action зарегистрирован в обе стороны", 1, relayed.count { it == action })
        }
    }

    /** Действие из таблицы, которого нет в фильтре DummyActivity, форвардится в никуда. */
    @Test
    fun relayedProfileActionsAreDeclaredByDummyActivity() {
        val handledElsewhere = setOf(
            ProfileActions.REFRESH_APP_LISTS, // MainActivity
            "io.gatekeeper.action.DELIVER_APP_LIST_REFRESH", // AppListRefreshReceiver
            "io.gatekeeper.action.BATCH_FREEZE_WORK", // BatchFreezeService
        )
        for ((action, _) in RELAY_ACTION_FILTERS) {
            if (action in handledElsewhere) continue
            assertTrue("$action не объявлен в фильтре DummyActivity", action in dummyActions)
        }
    }

    /**
     * Что любое приложение рабочего профиля может прислать личному Gatekeeper без подписи,
     * и что любое личное приложение -- рабочему. Набор меняется только вместе с тестом.
     */
    @Test
    fun unsignedCrossProfileSurfaceIsPinned() {
        assertEquals(
            setOf(
                ProfileActions.SHOW_TOAST,
                ProfileActions.REFRESH_MAIN_APP_LIST,
                ProfileActions.PUBLIC_FREEZE_ALL,
                ProfileActions.FINALIZE_PROVISION,
                ProfileActions.OPEN_MAIN_APP,
            ),
            actionsTo(TO_WORK) intersect RelayAuthorization.UNSIGNED_ACTIONS,
        )
        assertEquals(
            setOf(ProfileActions.REQUEST_REPAIR, ProfileActions.TRY_START_SERVICE),
            actionsTo(TO_PARENT) intersect RelayAuthorization.UNSIGNED_ACTIONS,
        )
    }

    /** S2: разморозка без подписи из соседнего профиля -- ни в одну сторону. */
    @Test
    fun publicUnfreezeIsNeverRelayed() {
        val relayed = RELAY_ACTION_FILTERS.map { it.first }.toSet()
        assertFalse(ProfileActions.PUBLIC_UNFREEZE_ALL in relayed)
        assertFalse(ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH in relayed)
    }

    /** Установка, удаление и стирание идут только из личного в рабочий, где их исполняет владелец профиля. */
    @Test
    fun privilegedActionsGoOnlyIntoTheWorkProfile() {
        val privileged = listOf(
            ProfileActions.INSTALL_PACKAGE,
            ProfileActions.UNINSTALL_PACKAGE,
            ProfileActions.WIPE_PROFILE,
        )
        for (action in privileged) {
            assertEquals(action, listOf(action to TO_PARENT), RELAY_ACTION_FILTERS.filter { it.first == action })
        }
    }
}

/**
 * F4: набор политик профиля пересобирается, только когда меняется отпечаток входов.
 * Настройка, не попавшая во входы, применялась бы лишь суточной сверкой.
 */
class WorkPolicyInputsTest {
    private lateinit var storage: LocalStorageManager

    @Before
    fun setUp() {
        TestStorage.install()
        storage = LocalStorageManager.getInstance()
    }

    private fun fingerprint() = PolicyFingerprint.of(WorkProfilePolicy.settingsInputs())

    @Test
    fun fingerprintIsStableForTheSameSettings() {
        assertEquals(fingerprint(), fingerprint())
    }

    /** Все, что читают фильтры, ContactsAccessPolicy и IsolationEnforcer. */
    @Test
    fun everyPolicySettingChangesTheFingerprint() {
        val settings = listOf(
            LocalStorageManager.PREF_SHARE_WORK_TO_PERSONAL,
            LocalStorageManager.PREF_CROSS_PROFILE_CONTENT_ACCESS,
            LocalStorageManager.PREF_CREATE_PERSONAL_CALENDAR_EVENTS,
            LocalStorageManager.PREF_OPEN_LINKS_IN_WORK,
            LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_ALL,
            LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING,
            LocalStorageManager.PREF_BLOCK_CALLER_ID,
        ) + IsolationPolicies.ALL.map { IsolationPolicies.prefName(it.key) }

        for (key in settings) {
            TestStorage.install()
            val before = fingerprint()
            LocalStorageManager.getInstance().setBoolean(key, true)
            assertNotEquals("$key не входит в отпечаток", before, fingerprint())
        }
    }

    @Test
    fun linkRulesChangeTheFingerprintButNotTheirOrder() {
        storage.setStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES, arrayOf("a.example", "b.example"))
        val both = fingerprint()
        storage.setStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES, arrayOf("b.example", "a.example"))
        assertEquals(both, fingerprint())
        storage.setStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES, arrayOf("a.example"))
        assertNotEquals(both, fingerprint())
    }

    /** Свои служебные отметки (отпечаток, время применения) во входы не попадают -- иначе вечная пересборка. */
    @Test
    fun ownBookkeepingDoesNotChangeTheFingerprint() {
        val before = fingerprint()
        storage.setStringNow("work_policy_fingerprint", before)
        storage.setLong("work_policy_applied_at", 1L)
        storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
        assertEquals(before, fingerprint())
    }
}
