package io.gatekeeper.debug

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver

/**
 * Опрос политик владельца профиля: что из стабильного набора DevicePolicyManager
 * платформа реально дает вызвать нам.
 *
 * Нужен потому, что документация не различает два случая. Формулировка
 * "device owner or profile owner" в javadoc покрывает и корпоративный профиль,
 * заведенный сервером EMM, и наш -- личный, заведенный самим пользователем из
 * APK. Часть вызовов во втором случае отвечает SecurityException, часть молча
 * не применяется, и отличить это можно только вызовом.
 *
 * Каждая проба ставит значение, читает обратно и возвращает как было: стенд
 * после прогона остается в прежнем состоянии.
 *
 * Запуск в рабочем профиле:
 *   adb shell am broadcast --user <profile> -n io.gatekeeper/io.gatekeeper.debug.PolicyProbeReceiver
 */
class PolicyProbeReceiver : BroadcastReceiver() {

    private class Outcome(val name: String, val verdict: String)

    override fun onReceive(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(
            context.applicationContext, GatekeeperDeviceAdminReceiver::class.java
        )
        if (!dpm.isProfileOwnerApp(context.packageName)) {
            Log.i(TAG, "$MARK not a profile owner here, nothing to probe")
            return
        }
        val results = mutableListOf<Outcome>()
        for (key in RESTRICTIONS) {
            results += probeRestriction(context, dpm, admin, key)
        }
        results += probeMethods(context, dpm, admin)
        Log.i(TAG, "$MARK ---- begin ----")
        for (r in results) {
            Log.i(TAG, "$MARK ${r.name.padEnd(44)} ${r.verdict}")
        }
        Log.i(TAG, "$MARK ---- end ----")
    }

    /** Ставим ограничение, читаем эффективный список, возвращаем как было. */
    private fun probeRestriction(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
    ): Outcome {
        val um = context.getSystemService(UserManager::class.java)
        val wasSet = um.getUserRestrictions().getBoolean(key, false)
        return try {
            dpm.addUserRestriction(admin, key)
            val applied = context.getSystemService(UserManager::class.java)
                .getUserRestrictions().getBoolean(key, false)
            if (!wasSet) dpm.clearUserRestriction(admin, key)
            if (applied) Outcome(key, "OK") else Outcome(key, "тихо не применилось")
        } catch (e: SecurityException) {
            Outcome(key, "SecurityException: ${e.message?.take(90)}")
        } catch (e: IllegalArgumentException) {
            Outcome(key, "IllegalArgument: ${e.message?.take(90)}")
        } catch (e: UnsupportedOperationException) {
            Outcome(key, "Unsupported: ${e.message?.take(90)}")
        }
    }

    private fun probeMethods(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ): List<Outcome> {
        val out = mutableListOf<Outcome>()
        out += probe("setCameraDisabled") {
            val was = dpm.getCameraDisabled(admin)
            dpm.setCameraDisabled(admin, true)
            val got = dpm.getCameraDisabled(admin)
            dpm.setCameraDisabled(admin, was)
            if (got) "OK" else "тихо не применилось"
        }
        out += probe("setScreenCaptureDisabled") {
            val was = dpm.getScreenCaptureDisabled(admin)
            dpm.setScreenCaptureDisabled(admin, true)
            val got = dpm.getScreenCaptureDisabled(admin)
            dpm.setScreenCaptureDisabled(admin, was)
            if (got) "OK" else "тихо не применилось"
        }
        out += probe("setPermissionPolicy(AUTO_DENY)") {
            val was = dpm.getPermissionPolicy(admin)
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY)
            val got = dpm.getPermissionPolicy(admin)
            dpm.setPermissionPolicy(admin, was)
            if (got == DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY) "OK" else "тихо не применилось"
        }
        out += probe("setBackupServiceEnabled(false)") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@probe "нужен API 26"

            val was = dpm.isBackupServiceEnabled(admin)
            dpm.setBackupServiceEnabled(admin, false)
            val got = dpm.isBackupServiceEnabled(admin)
            dpm.setBackupServiceEnabled(admin, was)
            if (!got) "OK" else "тихо не применилось"
        }
        out += probe("setKeyguardDisabledFeatures") {
            val was = dpm.getKeyguardDisabledFeatures(admin)
            dpm.setKeyguardDisabledFeatures(
                admin, DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS
            )
            val got = dpm.getKeyguardDisabledFeatures(admin)
            dpm.setKeyguardDisabledFeatures(admin, was)
            if (got and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS != 0) {
                "OK"
            } else {
                "тихо не применилось"
            }
        }
        out += probe("setMaximumTimeToLock") {
            val was = dpm.getMaximumTimeToLock(admin)
            dpm.setMaximumTimeToLock(admin, LOCK_PROBE_MS)
            val got = dpm.getMaximumTimeToLock(admin)
            dpm.setMaximumTimeToLock(admin, was)
            if (got == LOCK_PROBE_MS) "OK" else "тихо не применилось"
        }
        out += probe("setRequiredPasswordComplexity") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@probe "нужен API 31"

            val was = dpm.requiredPasswordComplexity
            dpm.requiredPasswordComplexity = DevicePolicyManager.PASSWORD_COMPLEXITY_LOW
            val got = dpm.requiredPasswordComplexity
            dpm.requiredPasswordComplexity = was
            if (got == DevicePolicyManager.PASSWORD_COMPLEXITY_LOW) "OK" else "тихо не применилось"
        }
        out += probe("setOrganizationName") {
            val was = dpm.getOrganizationName(admin)
            dpm.setOrganizationName(admin, "probe")
            val got = dpm.getOrganizationName(admin)
            dpm.setOrganizationName(admin, was)
            if (got?.toString() == "probe") "OK" else "тихо не применилось"
        }
        out += probe("setAccountManagementDisabled") {
            dpm.setAccountManagementDisabled(admin, ACCOUNT_TYPE_PROBE, true)
            val got = dpm.accountTypesWithManagementDisabled?.contains(ACCOUNT_TYPE_PROBE) == true
            dpm.setAccountManagementDisabled(admin, ACCOUNT_TYPE_PROBE, false)
            if (got) "OK" else "тихо не применилось"
        }
        out += probe("setUninstallBlocked(другой пакет)") {
            val victim = otherPackage(context) ?: return@probe "в профиле нет чужих пакетов"
            val was = dpm.isUninstallBlocked(admin, victim)
            dpm.setUninstallBlocked(admin, victim, true)
            val got = dpm.isUninstallBlocked(admin, victim)
            dpm.setUninstallBlocked(admin, victim, was)
            if (got) "OK ($victim)" else "тихо не применилось"
        }
        out += probe("setPackagesSuspended(другой пакет)") {
            val victim = otherPackage(context) ?: return@probe "в профиле нет чужих пакетов"
            val failed = dpm.setPackagesSuspended(admin, arrayOf(victim), true)
            val got = failed.isEmpty() && dpm.isPackageSuspended(admin, victim)
            dpm.setPackagesSuspended(admin, arrayOf(victim), false)
            if (got) "OK ($victim)" else "отказ: не применился к ${failed.toList()}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += probe("setNearbyNotificationStreamingPolicy") {
                val was = dpm.nearbyNotificationStreamingPolicy
                dpm.nearbyNotificationStreamingPolicy =
                    DevicePolicyManager.NEARBY_STREAMING_DISABLED
                val got = dpm.nearbyNotificationStreamingPolicy
                dpm.nearbyNotificationStreamingPolicy = was
                if (got == DevicePolicyManager.NEARBY_STREAMING_DISABLED) {
                    "OK"
                } else {
                    "тихо не применилось"
                }
            }
            out += probe("setNearbyAppStreamingPolicy") {
                val was = dpm.nearbyAppStreamingPolicy
                dpm.nearbyAppStreamingPolicy = DevicePolicyManager.NEARBY_STREAMING_DISABLED
                val got = dpm.nearbyAppStreamingPolicy
                dpm.nearbyAppStreamingPolicy = was
                if (got == DevicePolicyManager.NEARBY_STREAMING_DISABLED) {
                    "OK"
                } else {
                    "тихо не применилось"
                }
            }
        }
        return out
    }

    private fun probe(name: String, body: () -> String): Outcome = try {
        Outcome(name, body())
    } catch (e: SecurityException) {
        Outcome(name, "SecurityException: ${e.message?.take(200)}")
    } catch (e: IllegalArgumentException) {
        Outcome(name, "IllegalArgument: ${e.message?.take(200)}")
    } catch (e: UnsupportedOperationException) {
        Outcome(name, "Unsupported: ${e.message?.take(90)}")
    } catch (e: IllegalStateException) {
        Outcome(name, "IllegalState: ${e.message?.take(90)}")
    }

/** Любой чужой пакет профиля: сначала обычный, иначе любой системный. */
    private fun otherPackage(context: Context): String? {
        val all = context.packageManager.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
        return all.firstOrNull {
            it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
        }?.packageName ?: all.firstOrNull()?.packageName
    }

    private companion object {
        const val TAG = "PolicyProbe"

        /** Метка для grep: логи профиля перемешаны с системными. */
        const val MARK = "@@"
        const val LOCK_PROBE_MS = 600_000L
        const val ACCOUNT_TYPE_PROBE = "io.gatekeeper.probe"

        val RESTRICTIONS = listOf(
            UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            UserManager.DISALLOW_ASSIST_CONTENT,
            UserManager.DISALLOW_CONTENT_CAPTURE,
            UserManager.DISALLOW_CONTENT_SUGGESTIONS,
            UserManager.DISALLOW_SHARE_LOCATION,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNMUTE_MICROPHONE,
            UserManager.DISALLOW_OUTGOING_CALLS,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_CAMERA_TOGGLE,
            UserManager.DISALLOW_MICROPHONE_TOGGLE,
            UserManager.DISALLOW_PRINTING,
            UserManager.DISALLOW_AUTOFILL,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_SET_WALLPAPER,
        )
    }
}
