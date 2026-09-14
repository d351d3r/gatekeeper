package io.gatekeeper.ui

import io.gatekeeper.services.AntiSpyVpnWatchService
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.UnfreezeShortcuts
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.CrossProfileScheduler
import io.gatekeeper.util.ProfileNotifier
import io.gatekeeper.util.WorkProfilePolicy
import io.gatekeeper.util.WorkProfileBatchFreeze

/**
 * Релей «рабочая сторона» DummyActivity: действия, которые прилетают из
 * личного профиля через кросс-профильный форвардер и исполняются в рабочем
 * (профиль-оунер имеет DPM-привилегии). Вынесено из DummyActivity --
 * каждый обработчик трётся об intent extras и сервисы профиля.
 *
 * Персист списков автозаморозки живёт здесь же: u11 -- единственный контекст,
 * привилегированный для DevicePolicyManager, поэтому личная сторона только
 * шлёт список, а владение -- на этой стороне.
 */
class WorkRelayHandlers(private val activity: DummyActivity) {

    fun handleSynchronizePreference() {
        val intent = activity.intent
        val name = requireNotNull(intent.getStringExtra("name"))
        // Синхронно: настройки сторожа читает процесс :vpnwatch, который поднимается ниже.
        if (intent.hasExtra("boolean")) {
            LocalStorageManager.getInstance()
                .setBooleanNow(name, intent.getBooleanExtra("boolean", false))
        } else if (intent.hasExtra("int")) {
            LocalStorageManager.getInstance()
                .setIntNow(name, intent.getIntExtra("int", Int.MIN_VALUE))
        }
        SettingsManager.getInstance().applyAll()
        if (activity.isProfileOwnerInternal) {
            WorkProfilePolicy.enforceWorkProfilePolicies(activity)
        }
        if (name.startsWith(ANTI_SPY_PREF_PREFIX)) {
            AntiSpyVpnWatchService.syncState(activity)
        }
        activity.finish()
    }

    /**
     * Весь набор изоляции разом: в интенте лежат только включенные ключи, всё
     * остальное из таблицы выключается. Одно письмо на весь набор, потому что
     * реле поднимает activity, и двадцать три запуска подряд не доезжают.
     */
    fun handleSyncIsolation() {
        val enabled = activity.intent.getStringArrayExtra(ProfileActions.EXTRA_ISOLATION_KEYS)?.toSet().orEmpty()
        val storage = LocalStorageManager.getInstance()
        for (policy in IsolationPolicies.ALL) {
            storage.setBooleanNow(
                IsolationPolicies.prefName(policy.key),
                policy.key in enabled,
            )
        }
        if (activity.isProfileOwnerInternal) {
            WorkProfilePolicy.enforceWorkProfilePolicies(activity)
        }
        activity.finish()
    }

    fun handleRemoveUnfreezeShortcut() {
        val packageName = activity.intent.getStringExtra("packageName") ?: run {
            activity.finish()
            return
        }
        UnfreezeShortcuts.removeLauncherShortcuts(activity, packageName)
        if (!activity.isProfileOwnerInternal) {
            LocalStorageManager.getInstance().removeFromStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        }
        activity.finish()
    }

    fun handleSyncAntiSpyVpnWatch() {
        if (activity.isProfileOwnerInternal) {
            val storage = LocalStorageManager.getInstance()
            val list = activity.intent.getStringArrayExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)
            if (list != null) {
                storage.setStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, list)
            }
            AntiSpyVpnWatchService.syncState(activity)
        }
        activity.finish()
    }

    fun handleFreezeAllInList() {
        if (activity.isProfileOwnerInternal) {
            freezeListInWorkProfile()
        } else {
            activity.finish()
        }
    }

    private fun freezeListInWorkProfile() {
        val intent = activity.intent
        val list = intent.getStringArrayExtra("list") ?: run {
            activity.finish()
            return
        }
        // Persist the authoritative list into the work profile so the work-profile VPN
        // watcher can freeze on its own when a VPN comes up later (it is the only context
        // privileged to call DevicePolicyManager; cross-profile starts from personal are denied).
        LocalStorageManager.getInstance()
            .setStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, list)
        AntiSpyVpnWatchService.syncState(activity)
        val frozen = WorkProfileBatchFreeze.freezeList(activity, list)
        val stillVisible = WorkProfileBatchFreeze.countStillVisible(activity, list)
        // E-3: уведомление постим только для фоновой VPN-заморозки (пользователь может
        // быть в любом профиле). Ручной запрос ограничиваем тостом на личный профиль --
        // уведомление в шторке рабочего выглядит как "toast снизу" и дублирует тост.
        val vpnOrigin = intent.getBooleanExtra(DummyActivity.EXTRA_VPN_ORIGIN, false)
        if (stillVisible == 0) {
            CrossProfileScheduler.notifyVpnBatchFreezeSessionComplete(activity, frozen > 0)
        } else if (frozen > 0) {
            if (vpnOrigin) {
                Notifications.postVpnAutoFreezeSuccessAlert(activity)
            }
            ProfileNotifier.showToastOnMainProfile(activity, io.gatekeeper.R.string.freeze_all_success)
        }
        ProfileNotifier.scheduleAppListRefreshDelivery(activity)
        activity.finish()
    }

    fun handleUnfreezeAllInList() {
        if (!activity.isProfileOwnerInternal) {
            activity.finish()
            return
        }
        unfreezeList()
        activity.stopService(android.content.Intent(activity, io.gatekeeper.services.FreezeService::class.java))
        ProfileNotifier.showToastOnMainProfile(activity, io.gatekeeper.R.string.unfreeze_all_success)
        activity.finish()
    }

    private fun unfreezeList() {
        val list = activity.intent.getStringArrayExtra("list") ?: return
        val admin = activity.adminComponent()
        for (pkg in list) {
            if (pkg.isNullOrEmpty()) continue
            activity.policyManagerInternal.setApplicationHidden(admin, pkg, false)
        }
    }

    /**
     * Единая заморозка по блокировке (редизайн, шаг 5): при областях «список» / «весь профиль»
     * FreezeService обязан слушать экран постоянно, а не только между «Разморозить и запустить»
     * и заморозкой. Сеансовая область живёт по старому пути -- сервис поднимает Unfreeze & Launch.
     */
    fun handleSyncFreezeService() {
        if (activity.isProfileOwnerInternal) {
            val manager = SettingsManager.getInstance()
            val needed = manager.getAutoFreezeServiceEnabled() &&
                manager.getAutoFreezeScope() != io.gatekeeper.util.ScreenLockFreezeScope.SESSION
            val intentService = android.content.Intent(activity, io.gatekeeper.services.FreezeService::class.java)
            if (needed) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    activity.startForegroundService(intentService)
                } else {
                    activity.startService(intentService)
                }
            } else {
                activity.stopService(intentService)
            }
        }
        activity.finish()
    }

    /**
     * Паник-удаление профиля: как profile owner зовем wipeData -- уносит только
     * управляемый профиль, не устройство (полный сброс -- привилегия device owner,
     * которой у нас нет). После вызова процесс профиля умирает, finish может не долететь.
     */
    fun handleWipeProfile() {
        if (activity.isProfileOwnerInternal) {
            try {
                activity.policyManagerInternal.wipeData(0)
            } catch (e: SecurityException) {
                android.util.Log.w("WorkRelayHandlers", "wipeData refused", e)
            }
        }
        activity.finish()
    }

    private companion object {

        /** Ключи настроек сторожа: их приезд обязан поднять или снять сторож. */
        private const val ANTI_SPY_PREF_PREFIX = "anti_spy_"
    }
}
