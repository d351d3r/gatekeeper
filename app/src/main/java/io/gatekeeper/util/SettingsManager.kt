package io.gatekeeper.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.gatekeeper.services.AntiSpyVpnWatchService
import io.gatekeeper.services.PaymentStubService
import io.gatekeeper.ui.DummyActivity

class SettingsManager private constructor(context: Context) {
    private val storage: LocalStorageManager = LocalStorageManager.getInstance()
    // Синглтон переживает любую activity, поэтому держим контекст приложения:
    // ссылка на activity отсюда пережила бы ее саму. Все три startActivity ниже
    // ставят FLAG_ACTIVITY_NEW_TASK, так что контекста приложения им достаточно.
    private val context: Context = context.applicationContext

    /**
     * Все, что сеттеры делают за пределами своих настроек: письма в соседний профиль,
     * подъем сторожа VPN, включение компонентов. Настройка к этому моменту уже сохранена
     * локально, поэтому недоехавшая синхронизация разводит профили молча: тумблер
     * включен, а в другом профиле ничего не изменилось. JVM-тест подменяет эффекты
     * записью вызовов и проверяет, что каждый сеттер отправляет то же значение.
     */
    internal interface Effects {
        fun syncBoolean(name: String, value: Boolean)
        fun syncInt(name: String, value: Int)
        fun syncIsolation(enabledKeys: Array<String>)
        fun syncFreezeService()
        fun syncVpnWatch()
        fun setComponentEnabled(component: Class<*>, enabled: Boolean)
    }

    internal var effects: Effects = AndroidEffects()

    private inner class AndroidEffects : Effects {
        override fun syncBoolean(name: String, value: Boolean) = relay(
            Intent(DummyActivity.SYNCHRONIZE_PREFERENCE).putExtra("name", name).putExtra("boolean", value),
            "preference $name",
        )

        override fun syncInt(name: String, value: Int) = relay(
            Intent(DummyActivity.SYNCHRONIZE_PREFERENCE).putExtra("name", name).putExtra("int", value),
            "preference $name",
        )

        /**
         * Набор уезжает в профиль целиком и одним письмом: реле поднимает activity,
         * и двадцать три запуска подряд сталкиваются друг с другом.
         */
        override fun syncIsolation(enabledKeys: Array<String>) = relay(
            Intent(DummyActivity.SYNC_ISOLATION).putExtra(ProfileActions.EXTRA_ISOLATION_KEYS, enabledKeys),
            "isolation policies",
        )

        /**
         * Рабочий профиль поднимает/останавливает FreezeService по факту настроек.
         * Из рабочего процесса письмо уйдёт в личный, где оно безвредно no-op.
         */
        override fun syncFreezeService() = relay(Intent(DummyActivity.SYNC_FREEZE_SERVICE), "freeze service sync")

        override fun syncVpnWatch() {
            AntiSpyVpnWatchService.syncState(context)
        }

        override fun setComponentEnabled(component: Class<*>, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, component),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
        }

        // Что именно не доехало -- в логе: общего предупреждения о реле для разбора мало.
        private fun relay(intent: Intent, what: String) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (!Utility.tryTransferIntentToProfile(context, intent)) {
                Log.w(TAG, "$what is not synced to the other profile")
                return
            }
            context.startActivity(intent)
        }
    }

    fun applyAll() {
        applyCrossProfileFileChooser()
        applyPaymentStub()
    }

    fun applyCrossProfileFileChooser() {
        effects.setComponentEnabled(
            CrossProfileDocumentsProvider::class.java,
            storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER),
        )
    }

    fun setCrossProfileFileChooserEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
        applyCrossProfileFileChooser()
        effects.syncBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
    }

    fun getCrossProfileFileChooserEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)

    fun setCrossProfileContentAccessEnabled(enabled: Boolean) {
        storage.setBooleanNow(LocalStorageManager.PREF_CROSS_PROFILE_CONTENT_ACCESS, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_CROSS_PROFILE_CONTENT_ACCESS, enabled)
    }

    fun getCrossProfileContentAccessEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_CONTENT_ACCESS)

    fun setCreatePersonalCalendarEventsEnabled(enabled: Boolean) {
        storage.setBooleanNow(LocalStorageManager.PREF_CREATE_PERSONAL_CALENDAR_EVENTS, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_CREATE_PERSONAL_CALENDAR_EVENTS, enabled)
    }

    fun getCreatePersonalCalendarEventsEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CREATE_PERSONAL_CALENDAR_EVENTS)

    fun setCrossProfileInteractionAllEnabled(enabled: Boolean) {
        storage.setBooleanNow(LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_ALL, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_ALL, enabled)
    }

    fun getCrossProfileInteractionAllEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_INTERACTION_ALL)

    fun setBlockContactsSearchingEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
    }

    fun getBlockContactsSearchingEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING)

    fun setBlockCallerIdEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CALLER_ID, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_BLOCK_CALLER_ID, enabled)
    }

    fun getBlockCallerIdEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CALLER_ID)

    fun setShareWorkToPersonalEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_SHARE_WORK_TO_PERSONAL, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_SHARE_WORK_TO_PERSONAL, enabled)
    }

    fun getShareWorkToPersonalEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_SHARE_WORK_TO_PERSONAL)

    fun setOpenLinksInWorkEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_OPEN_LINKS_IN_WORK, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_OPEN_LINKS_IN_WORK, enabled)
    }

    fun getOpenLinksInWorkEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_OPEN_LINKS_IN_WORK)

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
        effects.syncFreezeService()
    }

    fun getAutoFreezeServiceEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE)

    fun setAutoFreezeDelay(seconds: Int) {
        storage.setInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
        effects.syncInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
    }

    fun getAutoFreezeDelay(): Int {
        var ret = storage.getInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY)
        if (ret == Int.MIN_VALUE) {
            ret = 0
        }
        return ret
    }

    /**
     * Область заморозки по блокировке. Геттер -- точка входа разовой миграции из
     * старого триггера сторожа VPN: вызывается и из UI, и из FreezeService.
     */
    fun setAutoFreezeScope(scope: ScreenLockFreezeScope) {
        storage.setIntNow(LocalStorageManager.PREF_AUTO_FREEZE_SCOPE, scope.stored)
        effects.syncInt(LocalStorageManager.PREF_AUTO_FREEZE_SCOPE, scope.stored)
        effects.syncFreezeService()
    }

    fun getAutoFreezeScope(): ScreenLockFreezeScope {
        ScreenLockFreezeMigration.run(storage)?.let { decision ->
            setAutoFreezeServiceEnabled(decision.enableService)
            setAutoFreezeScope(decision.scope)
            setAutoFreezeDelay(decision.delaySeconds)
        }
        return ScreenLockFreezeScope.fromStored(
            storage.getInt(LocalStorageManager.PREF_AUTO_FREEZE_SCOPE)
        )
    }

    fun setSkipForegroundEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
        effects.syncBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
    }

    fun getSkipForegroundEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND)

    /**
     * Сторож VPN. Настройка нужна обоим профилям: в личном она решает, поднимать ли сторож,
     * в рабочем -- что и когда морозить, поэтому каждая пишется и синхронизируется.
     * После записи сторож в своем профиле приводится в соответствие настройке.
     */
    fun getAntiSpyWatchConfig(): AntiSpyWatchConfig = AntiSpyManager.readWatchConfig(context)

    fun setAntiSpyWatchEnabled(enabled: Boolean) =
        applyWatchBool(LocalStorageManager.PREF_ANTI_SPY_VPN_WATCH_ENABLED, enabled)

    fun setAntiSpyFreezeOnVpn(enabled: Boolean) =
        applyWatchBool(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_VPN, enabled)

    fun setAntiSpyNotifyOnly(enabled: Boolean) =
        applyWatchBool(LocalStorageManager.PREF_ANTI_SPY_NOTIFY_ONLY, enabled)

    fun setAntiSpyFreezeScope(scope: AntiSpyFreezeScope) =
        applyWatchInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_SCOPE, scope.stored)

    fun setAntiSpyFreezeDelay(seconds: Int) =
        applyWatchInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY, seconds)

    /**
     * Политика изоляции: пишем локально, отправляем в профиль и просим его
     * применить набор заново. Применяет их рабочая сторона в
     * [io.gatekeeper.util.IsolationEnforcer], ей и нужен свежий префикс.
     */
    fun setIsolationPolicy(key: String, enabled: Boolean) {
        storage.setBooleanNow(IsolationPolicies.prefName(key), enabled)
        syncIsolationToProfile()
    }

    fun getIsolationPolicy(key: String): Boolean =
        storage.getBoolean(IsolationPolicies.prefName(key))

    /** Текущий набор целиком: нужен экрану, чтобы узнать пресет. */
    fun getIsolationState(): Map<String, Boolean> =
        IsolationPolicies.ALL.associate { it.key to getIsolationPolicy(it.key) }

    fun applyIsolationPreset(isolated: Boolean) {
        for ((key, value) in IsolationPolicies.preset(isolated)) {
            storage.setBooleanNow(IsolationPolicies.prefName(key), value)
        }
        syncIsolationToProfile()
    }

    private fun syncIsolationToProfile() {
        val enabled = IsolationPolicies.ALL
            .filter { getIsolationPolicy(it.key) }
            .map { it.key }
            .toTypedArray()
        effects.syncIsolation(enabled)
    }

    // Запись синхронная: сторож живет в отдельном процессе и читает настройку сразу же,
    // на следующей строке.
    private fun applyWatchBool(name: String, value: Boolean) {
        storage.setBooleanNow(name, value)
        effects.syncBoolean(name, value)
        effects.syncVpnWatch()
    }

    private fun applyWatchInt(name: String, value: Int) {
        storage.setIntNow(name, value)
        effects.syncInt(name, value)
        effects.syncVpnWatch()
    }

    fun getPaymentStubEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)

    fun setPaymentStubEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, enabled)
        applyPaymentStub()
    }

    fun applyPaymentStub() {
        effects.setComponentEnabled(
            PaymentStubService::class.java,
            storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB),
        )
    }

    companion object {
        private const val TAG = "SettingsManager"

        // Утечки тут нет: конструктор кладет в поле контекст приложения, а он живет
        // столько же, сколько процесс. Линт смотрит на тип поля, а не на то, что в нем.
        @SuppressLint("StaticFieldLeak")
        private var instance: SettingsManager? = null

        fun initialize(context: Context) {
            instance = SettingsManager(context)
        }

        fun getInstance(): SettingsManager {
            return instance
                ?: throw IllegalStateException("SettingsManager must be initialized at start-up")
        }
    }
}
