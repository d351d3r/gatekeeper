package io.gatekeeper.ui

import android.content.Intent
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.CrossProfileScheduler
import io.gatekeeper.util.Utility

/**
 * Релей «личная сторона» DummyActivity: публичные batch-действия
 * (ярлыки Freeze/Unfreeze all), которые личный профиль пересылает в рабочий,
 * плюс завершение VPN-сеанса заморозки. Вынесено из DummyActivity.
 */
class DummyBatchFlow(private val activity: DummyActivity) {

    /** Действие только для личной стороны: в профиле -- тихий отказ, не падение. */
    private fun rejectedInProfile(action: String): Boolean {
        if (!activity.isProfileOwnerInternal) return false
        android.util.Log.w("DummyBatchFlow", "personal-side-only $action reached the profile owner")
        activity.finish()
        return true
    }

    fun handlePublicFreezeAll() {
        // Публичное действие без подписи: в рабочем профиле его может прислать любое
        // приложение. Раньше здесь стоял error(...) -- и любое приложение профиля роняло
        // процесс Gatekeeper вместе со сторожами (S3, docs/audit_android17.md).
        if (rejectedInProfile(DummyActivity.PUBLIC_FREEZE_ALL)) return
        if (activity.forwardBatchToMainActivityIfVisible(DummyActivity.PUBLIC_FREEZE_ALL)) return
        AntiSpyManager.syncAutoFreezeListToWorkProfile(activity)
        CrossProfileScheduler.launchFreezeInWorkProfile(activity, AntiSpyManager.getAutoFreezeList(activity))
        activity.finishBatchShortcutFlow()
    }

    fun handlePublicUnfreezeAll() {
        if (rejectedInProfile(DummyActivity.PUBLIC_UNFREEZE_ALL) ||
            activity.forwardBatchToMainActivityIfVisible(DummyActivity.PUBLIC_UNFREEZE_ALL)
        ) {
            return
        }
        if (!activity.antiSpyFlowInternal.ensureVpnPermissionThenLaunch()) {
            return
        }
        AntiSpyLaunchGate.runBeforeLaunch(
            activity, LocalStorageManager.getInstance(), "",
            {
                // «list» входит в AuthPayload.SIGNED_EXTRA_KEYS: подпись считается
                // по extras, поэтому список кладём ДО transferIntentToProfile.
                // Иначе u11 пересчитает payload с «list», verify не сойдётся и
                // разморозка молча не случится (batch unfreeze после заморозки).
                val forwardIntent = Intent(DummyActivity.UNFREEZE_ALL_IN_LIST)
                val list = LocalStorageManager.getInstance()
                    .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                forwardIntent.putExtra("list", list)
                if (Utility.tryTransferIntentToProfile(activity, forwardIntent)) {
                    activity.startActivity(forwardIntent)
                }
                activity.finishBatchShortcutFlow()
            },
            { reason ->
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    if (activity.antiSpyFlowInternal.ensureVpnPermissionThenLaunch()) {
                        handlePublicUnfreezeAll()
                    }
                    return@runBeforeLaunch
                }
                activity.finish()
            }
        )
    }

    /** Work profile -> personal is handled on the work side; personal side only acks. */
    fun handleVpnSessionComplete() {
        if (!activity.isProfileOwnerInternal) {
            CrossProfileScheduler.deliverVpnBatchFreezeSessionComplete(activity)
            activity.finishBatchShortcutFlow()
        } else {
            activity.finish()
        }
    }
}
