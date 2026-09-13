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

    // Настройка уже сохранена локально, поэтому недоехавшая синхронизация разводит профили
    // молча: тумблер включен, а в другом профиле ничего не изменилось. Имя настройки в логе --
    // единственный способ это разобрать, общего предупреждения о реле для этого мало.
    private fun syncSettingsToProfileBool(name: String, value: Boolean) {
        val intent = Intent(DummyActivity.SYNCHRONIZE_PREFERENCE)
        intent.putExtra("name", name)
        intent.putExtra("boolean", value)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (!Utility.tryTransferIntentToProfile(context, intent)) {
            Log.w(TAG, "preference $name is not synced to the other profile")
            return
        }
        context.startActivity(intent)
    }

    private fun syncSettingsToProfileInt(name: String, value: Int) {
        val intent = Intent(DummyActivity.SYNCHRONIZE_PREFERENCE)
        intent.putExtra("name", name)
        intent.putExtra("int", value)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (!Utility.tryTransferIntentToProfile(context, intent)) {
            Log.w(TAG, "preference $name is not synced to the other profile")
            return
        }
        context.startActivity(intent)
    }

    fun applyAll() {
        applyCrossProfileFileChooser()
        applyPaymentStub()
    }

    fun applyCrossProfileFileChooser() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, CrossProfileDocumentsProvider::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }

    fun setCrossProfileFileChooserEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
        applyCrossProfileFileChooser()
        syncSettingsToProfileBool(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
    }

    fun getCrossProfileFileChooserEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)

    fun setBlockContactsSearchingEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
    }

    fun getBlockContactsSearchingEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING)

    fun setBlockCallerIdEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CALLER_ID, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_BLOCK_CALLER_ID, enabled)
    }

    fun getBlockCallerIdEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CALLER_ID)

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
        requestFreezeServiceSync()
    }

    fun getAutoFreezeServiceEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE)

    fun setAutoFreezeDelay(seconds: Int) {
        storage.setInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
        syncSettingsToProfileInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
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
        syncSettingsToProfileInt(LocalStorageManager.PREF_AUTO_FREEZE_SCOPE, scope.stored)
        requestFreezeServiceSync()
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

    /**
     * Рабочий профиль поднимает/останавливает FreezeService по факту настроек.
     * Из рабочего процесса письмо уйдёт в личный, где оно безвредно no-op.
     */
    private fun requestFreezeServiceSync() {
        val intent = Intent(DummyActivity.SYNC_FREEZE_SERVICE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (!Utility.tryTransferIntentToProfile(context, intent)) {
            Log.w(TAG, "freeze service sync is not delivered to the other profile")
            return
        }
        context.startActivity(intent)
    }

    fun setSkipForegroundEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
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

    /**
     * Набор уезжает в профиль целиком и одним письмом: реле поднимает activity,
     * и двадцать три запуска подряд сталкиваются друг с другом.
     */
    private fun syncIsolationToProfile() {
        val enabled = IsolationPolicies.ALL
            .filter { getIsolationPolicy(it.key) }
            .map { it.key }
            .toTypedArray()
        val intent = Intent(DummyActivity.SYNC_ISOLATION)
        intent.putExtra(ProfileActions.EXTRA_ISOLATION_KEYS, enabled)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (!Utility.tryTransferIntentToProfile(context, intent)) {
            Log.w(TAG, "isolation policies are not synced to the profile")
            return
        }
        context.startActivity(intent)
    }

    // Запись синхронная: сторож живет в отдельном процессе и читает настройку сразу же,
    // на следующей строке.
    private fun applyWatchBool(name: String, value: Boolean) {
        storage.setBooleanNow(name, value)
        syncSettingsToProfileBool(name, value)
        AntiSpyVpnWatchService.syncState(context)
    }

    private fun applyWatchInt(name: String, value: Int) {
        storage.setIntNow(name, value)
        syncSettingsToProfileInt(name, value)
        AntiSpyVpnWatchService.syncState(context)
    }

    fun getPaymentStubEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)

    fun setPaymentStubEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, enabled)
        applyPaymentStub()
    }

    fun applyPaymentStub() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, PaymentStubService::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
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
