package io.gatekeeper.util

import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.ApplicationInfoWrapper

/**
 * Что морозит единственный владелец триггера блокировки экрана -- [io.gatekeeper.services.FreezeService].
 * До редизайна (шаг 5) заморозку по блокировке обслуживали два сервиса с разными
 * областями и задержками; теперь выбор один и живёт на экране «Заморозка».
 */
enum class ScreenLockFreezeScope(val stored: Int) {
    /** Приложения, запущенные через «Разморозить и запустить» в текущем сеансе. */
    SESSION(0),

    /** Тот же список, что у кнопки-снежинки. */
    AUTO_FREEZE_LIST(1),

    /** Все сторонние приложения рабочего профиля. */
    WHOLE_WORK_PROFILE(2);

    companion object {
        fun fromStored(value: Int): ScreenLockFreezeScope =
            entries.firstOrNull { it.stored == value } ?: SESSION
    }
}

/**
 * Перенос включённой заморозки по блокировке из сторожа VPN (настройки
 * `anti_spy_freeze_on_screen_lock`) в единую модель экрана «Заморозка».
 * Решение вынесено в чистую функцию: цена ошибки -- погашенные без спроса
 * уведомления мессенджера и банков.
 */
object ScreenLockFreezeMigration {
    /** Что записать в настройки автозаморозки; null -- переносить нечего. */
    data class Decision(
        val enableService: Boolean,
        val scope: ScreenLockFreezeScope,
        val delaySeconds: Int,
    )

    fun decide(
        screenLockEnabled: Boolean,
        notifyOnly: Boolean,
        wholeProfileScope: Boolean,
        legacyDelaySeconds: Int,
    ): Decision? {
        if (!screenLockEnabled || notifyOnly) return null
        val scope = if (wholeProfileScope) {
            ScreenLockFreezeScope.WHOLE_WORK_PROFILE
        } else {
            ScreenLockFreezeScope.AUTO_FREEZE_LIST
        }
        return Decision(
            enableService = true,
            scope = scope,
            delaySeconds = nearestUnifiedDelay(legacyDelaySeconds),
        )
    }

    /** 0/60/120/300 -- шкала единой задержки; вне её -- ближайший выбор, не быстрее привычного. */
    private fun nearestUnifiedDelay(legacySeconds: Int): Int = when (legacySeconds) {
        DELAY_NONE -> DELAY_NONE
        DELAY_ONE_MINUTE -> DELAY_ONE_MINUTE
        DELAY_TWO_MINUTES -> DELAY_TWO_MINUTES
        DELAY_FIVE_MINUTES -> DELAY_FIVE_MINUTES
        else -> DELAY_ONE_MINUTE
    }

    /** Применяет решение один раз; повторные вызовы -- no-op. */
    fun run(storage: LocalStorageManager) {
        if (storage.getBoolean(LocalStorageManager.PREF_SCREEN_LOCK_FREEZE_MIGRATED)) return
        val decision = decide(
            screenLockEnabled = storage.getBoolean(
                LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK
            ),
            notifyOnly = storage.getBoolean(LocalStorageManager.PREF_ANTI_SPY_NOTIFY_ONLY),
            wholeProfileScope = storage.getInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_SCOPE) ==
                AntiSpyFreezeScope.WHOLE_WORK_PROFILE.stored,
            legacyDelaySeconds = storage.getInt(LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY),
        )
        if (decision != null) {
            storage.setBooleanNow(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, true)
            storage.setIntNow(
                LocalStorageManager.PREF_AUTO_FREEZE_SCOPE,
                decision.scope.stored,
            )
            storage.setIntNow(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, decision.delaySeconds)
        }
        storage.setBooleanNow(LocalStorageManager.PREF_SCREEN_LOCK_FREEZE_MIGRATED, true)
    }

    private const val DELAY_NONE = 0
    private const val DELAY_ONE_MINUTE = 60
    private const val DELAY_TWO_MINUTES = 120
    private const val DELAY_FIVE_MINUTES = 300
}

object AutoFreezePolicy {
    fun isInAutoFreezeList(packageName: String): Boolean =
        LocalStorageManager.getInstance().stringListContains(
            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
            packageName
        )

    /**
     * VPN gate applies to auto-freeze apps, batch unfreeze (null/empty package), and forced paths
     * such as clone into the work profile.
     */
    fun shouldApplyVpnGate(packageName: String?, forceGate: Boolean = false): Boolean {
        if (forceGate) return true
        if (packageName.isNullOrEmpty()) return true
        return isInAutoFreezeList(packageName)
    }

    fun migrateLegacyFrozenWithoutAutoFreeze(
        service: IGatekeeperService,
        apps: List<ApplicationInfoWrapper>
    ) {
        val storage = LocalStorageManager.getInstance()
        if (storage.getBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE)) {
            return
        }
        val complete = LegacyFreezeMigration.run(
            items = apps,
            requiresAction = { app ->
                app.isHidden() && !isInAutoFreezeList(app.getPackageName())
            },
            action = { app -> service.unfreezeApp(app) }
        )
        if (complete) {
            storage.setBooleanNow(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE, true)
        }
    }

    /**
     * Work profile list order: frozen → unfrozen with auto-freeze (snowflake) → rest; A–Z within tier.
     */
    fun sortWorkProfileApps(
        apps: MutableList<ApplicationInfoWrapper>,
        autoFreezePackages: Set<String>
    ) {
        apps.sortWith { x, y ->
            val tierCompare = workProfileSortTier(x, autoFreezePackages)
                .compareTo(workProfileSortTier(y, autoFreezePackages))
            if (tierCompare != 0) {
                return@sortWith tierCompare
            }
            x.getLabel()!!.compareTo(y.getLabel()!!, ignoreCase = true)
        }
    }

    private fun workProfileSortTier(app: ApplicationInfoWrapper, autoFreezePackages: Set<String>): Int =
        when {
            app.isHidden() -> 0
            autoFreezePackages.contains(app.getPackageName()) -> 1
            else -> 2
        }
}
