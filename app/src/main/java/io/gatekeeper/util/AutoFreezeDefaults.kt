package io.gatekeeper.util

import android.content.Context

/**
 * Auto-freeze list for the work profile (stored in main-profile [LocalStorageManager]).
 * Fully manual: apps join the list only via the user's snowflake toggle; the opt-out set
 * survives reinstalls and is respected by every freeze trigger.
 */
object AutoFreezeDefaults {
    fun enableForWorkProfile(packageName: String?, clearOptOut: Boolean = false) {
        if (packageName.isNullOrEmpty()) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (clearOptOut) {
            removeOptOut(packageName)
        } else if (isOptedOut(packageName)) {
            return
        }
        if (!storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
            AntiSpyManager.invalidateAutoFreezeListSync()
        }
    }

    fun enableForWorkProfile(context: Context?, packageName: String?, clearOptOut: Boolean = false) {
        enableForWorkProfile(packageName, clearOptOut)
        if (context != null) {
            AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext, force = true)
        }
    }

    fun optOutOfAutoFreeze(packageName: String) {
        if (packageName.isEmpty()) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (!storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
                packageName
            )
        }
    }

    fun isOptedOut(packageName: String): Boolean =
        LocalStorageManager.getInstance().stringListContains(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )

    fun removeOptOut(packageName: String) {
        LocalStorageManager.getInstance().removeFromStringList(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )
    }

    /** Drop all per-package state (opt-out etc.) when the package leaves the profile. */
    fun clearWorkProfilePackageTracking(packageName: String) {
        LocalStorageManager.getInstance().removeFromStringList(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )
    }
}
