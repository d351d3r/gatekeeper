package io.gatekeeper

import android.content.pm.ApplicationInfo
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.AutoFreezePolicy
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Список автозаморозки ведет только пользователь (снежинка); отказ от нее переживает
 * переустановку и уважается всеми триггерами. Ошибка здесь -- банк или мессенджер,
 * замороженные без спроса, или приложение, которое перестало замерзать.
 */
class AutoFreezeRulesTest {
    private lateinit var storage: LocalStorageManager

    @Before
    fun setUp() {
        TestStorage.install()
        storage = LocalStorageManager.getInstance()
    }

    private fun autoFreezeList() =
        storage.getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE).toList()

    private fun optOuts() =
        storage.getStringList(LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE).toList()

    @Test
    fun enablingTwiceKeepsOneEntry() {
        AutoFreezeDefaults.enableForWorkProfile("com.bank")
        AutoFreezeDefaults.enableForWorkProfile("com.bank")
        assertEquals(listOf("com.bank"), autoFreezeList())
        assertTrue(AutoFreezePolicy.isInAutoFreezeList("com.bank"))
    }

    /** Пользователь снял снежинку -- неявное включение ее не возвращает. */
    @Test
    fun optedOutPackageIsNotReEnabledImplicitly() {
        AutoFreezeDefaults.optOutOfAutoFreeze("com.bank")
        AutoFreezeDefaults.enableForWorkProfile("com.bank")
        assertTrue(autoFreezeList().isEmpty())
        assertTrue(AutoFreezeDefaults.isOptedOut("com.bank"))
    }

    /** Явное включение снежинки снимает отказ. */
    @Test
    fun explicitEnableClearsTheOptOut() {
        AutoFreezeDefaults.optOutOfAutoFreeze("com.bank")
        AutoFreezeDefaults.enableForWorkProfile("com.bank", clearOptOut = true)
        assertEquals(listOf("com.bank"), autoFreezeList())
        assertFalse(AutoFreezeDefaults.isOptedOut("com.bank"))
    }

    @Test
    fun optOutIsIdempotentAndForgottenWhenThePackageLeaves() {
        AutoFreezeDefaults.optOutOfAutoFreeze("com.bank")
        AutoFreezeDefaults.optOutOfAutoFreeze("com.bank")
        assertEquals(listOf("com.bank"), optOuts())
        AutoFreezeDefaults.clearWorkProfilePackageTracking("com.bank")
        assertFalse(AutoFreezeDefaults.isOptedOut("com.bank"))
    }

    @Test
    fun emptyPackageNamesAreIgnored() {
        AutoFreezeDefaults.enableForWorkProfile(null)
        AutoFreezeDefaults.enableForWorkProfile("")
        AutoFreezeDefaults.optOutOfAutoFreeze("")
        assertTrue(autoFreezeList().isEmpty())
        assertTrue(optOuts().isEmpty())
    }

    /** VPN-гейт: пакеты со снежинкой, пакетная разморозка (без пакета) и принудительные пути. */
    @Test
    fun vpnGateCoversAutoFreezeAppsBatchAndForcedPaths() {
        AutoFreezeDefaults.enableForWorkProfile("com.bank")
        assertTrue(AutoFreezePolicy.shouldApplyVpnGate("com.bank"))
        assertFalse(AutoFreezePolicy.shouldApplyVpnGate("com.game"))
        assertTrue(AutoFreezePolicy.shouldApplyVpnGate("com.game", forceGate = true))
        assertTrue(AutoFreezePolicy.shouldApplyVpnGate(null))
        assertTrue(AutoFreezePolicy.shouldApplyVpnGate(""))
    }

    /** Старые версии морозили без снежинки: такие размораживаются, ровно один раз. */
    @Test
    fun legacyMigrationUnfreezesOnlyFrozenAppsWithoutSnowflakeOnce() {
        AutoFreezeDefaults.enableForWorkProfile("com.bank")
        val apps = listOf(
            app("com.bank", hidden = true),
            app("com.old", hidden = true),
            app("com.live", hidden = false),
        )
        val service = RecordingService()

        AutoFreezePolicy.migrateLegacyFrozenWithoutAutoFreeze(service.proxy, apps)
        AutoFreezePolicy.migrateLegacyFrozenWithoutAutoFreeze(service.proxy, apps)

        assertEquals(listOf("com.old"), service.unfrozen)
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE))
    }

    /** Сервис рабочего профиля умер посередине -- миграция не отмечается пройденной. */
    @Test
    fun failedLegacyMigrationIsRetriedNextTime() {
        val apps = listOf(app("com.old", hidden = true))
        val service = RecordingService(fail = true)

        AutoFreezePolicy.migrateLegacyFrozenWithoutAutoFreeze(service.proxy, apps)
        assertFalse(storage.getBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE))

        service.fail = false
        AutoFreezePolicy.migrateLegacyFrozenWithoutAutoFreeze(service.proxy, apps)
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE))
        assertEquals(listOf("com.old"), service.unfrozen)
    }

    private fun app(packageName: String, hidden: Boolean): ApplicationInfoWrapper =
        ApplicationInfoWrapper(ApplicationInfo().also { it.packageName = packageName }).setHidden(hidden)

    /** Сервис-заглушка: пишет вызовы unfreezeApp, на остальное отвечает отказом. */
    private class RecordingService(var fail: Boolean = false) {
        val unfrozen = ArrayList<String>()

        val proxy: IGatekeeperService = Proxy.newProxyInstance(
            IGatekeeperService::class.java.classLoader,
            arrayOf(IGatekeeperService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unfreezeApp" -> {
                    if (fail) throw android.os.RemoteException()
                    unfrozen += (args[0] as ApplicationInfoWrapper).getPackageName()
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as IGatekeeperService
    }
}
