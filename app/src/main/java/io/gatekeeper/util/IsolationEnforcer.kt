package io.gatekeeper.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver

/**
 * Применение политик изоляции в рабочем профиле. Состав описан в [IsolationPolicies],
 * здесь только вызовы DevicePolicyManager.
 *
 * Каждая политика применяется отдельно и в своем try: отказ одной не должен уносить
 * остальные. Прошивки режут разное, и молчаливый отказ одной политики не повод
 * оставить профиль без остальных пятнадцати.
 */
object IsolationEnforcer {
    private const val TAG = "IsolationEnforcer"

    fun apply(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProfileOwnerApp(context.packageName)) return
        val admin = ComponentName(
            context.applicationContext, GatekeeperDeviceAdminReceiver::class.java
        )
        val storage = LocalStorageManager.getInstance()
        for (policy in IsolationPolicies.ALL) {
            val on = storage.getBoolean(IsolationPolicies.prefName(policy.key))
            try {
                if (policy.kind == IsolationPolicies.KIND_RESTRICTION) {
                    applyRestriction(dpm, admin, policy.key, on)
                } else {
                    applyMethod(dpm, admin, policy.key, on)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "policy ${policy.key} refused", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "policy ${policy.key} rejected", e)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "policy ${policy.key} unsupported", e)
            }
        }
    }

    private fun applyRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        on: Boolean,
    ) {
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }

    private fun applyMethod(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        on: Boolean,
    ) {
        when (key) {
            IsolationPolicies.METHOD_CAMERA -> dpm.setCameraDisabled(admin, on)
            IsolationPolicies.METHOD_SCREEN_CAPTURE -> dpm.setScreenCaptureDisabled(admin, on)
            IsolationPolicies.METHOD_PERMISSION_AUTO_DENY -> dpm.setPermissionPolicy(
                admin,
                if (on) {
                    DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
                } else {
                    DevicePolicyManager.PERMISSION_POLICY_PROMPT
                },
            )
            IsolationPolicies.METHOD_BACKUP_OFF ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dpm.setBackupServiceEnabled(admin, !on)
                }
            IsolationPolicies.METHOD_NEARBY_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dpm.nearbyNotificationStreamingPolicy = streaming(on)
                }
            IsolationPolicies.METHOD_NEARBY_APPS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dpm.nearbyAppStreamingPolicy = streaming(on)
                }
        }
    }

    private fun streaming(off: Boolean): Int = if (off) {
        DevicePolicyManager.NEARBY_STREAMING_DISABLED
    } else {
        DevicePolicyManager.NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY
    }
}
