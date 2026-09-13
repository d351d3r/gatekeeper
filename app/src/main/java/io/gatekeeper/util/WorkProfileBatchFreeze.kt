package io.gatekeeper.util

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.services.FreezeService

/**
 * Freeze all packages in the auto-freeze list inside the work profile without starting an Activity.
 * Safe to call from a background foreground service (VPN watcher).
 */
object WorkProfileBatchFreeze {
    private const val TAG = "WorkProfileBatchFreeze"

    private enum class FreezeOutcome {
        NEWLY,
        RECONCILED,
        ALREADY,
        STILL_VISIBLE,
    }

    fun freezeList(context: Context, list: Array<String>): Int {
        val session = FreezeSession.create(context, list) ?: return 0
        val counter = FreezeCounter(session.dpm, session.admin, context)
        session.normalized.forEach { pkg ->
            if (pkg.isNotEmpty()) counter.freeze(pkg)
        }
        stopFreezeService(context)
        counter.logResult(session.normalized.size)
        return counter.newlyFrozen + counter.reconciled
    }

    /** Нормализованный запрос на заморозку с проверками профиля; null -- выход с нулем. */
    private class FreezeSession private constructor(
        val normalized: Array<String>,
        val dpm: DevicePolicyManager,
        val admin: ComponentName,
    ) {
        companion object {
            fun create(context: Context, list: Array<String>): FreezeSession? {
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val normalized = Utility.normalizeStringList(list)
                return when {
                    !AntiSpyManager.isWorkProfile(context) -> {
                        Log.w(TAG, "freezeList called outside work profile")
                        null
                    }
                    normalized.isEmpty() -> {
                        Log.w(TAG, "freezeList: empty list")
                        null
                    }
                    dpm == null -> null
                    else -> FreezeSession(
                        normalized,
                        dpm,
                        ComponentName(context, GatekeeperDeviceAdminReceiver::class.java)
                    )
                }
            }
        }
    }

    /** Счетчик исходов заморозки; выносит цикл из [freezeList], чтобы тот остался плоским. */
    private class FreezeCounter(
        private val dpm: DevicePolicyManager,
        private val admin: ComponentName,
        private val context: Context,
    ) {
        var newlyFrozen = 0
            private set
        var reconciled = 0
            private set
        private var alreadyHidden = 0
        private var stillVisible = 0

        fun freeze(pkg: String) {
            try {
                when (freezePackage(dpm, admin, context, pkg)) {
                    FreezeOutcome.NEWLY -> newlyFrozen++
                    FreezeOutcome.RECONCILED -> reconciled++
                    FreezeOutcome.ALREADY -> alreadyHidden++
                    FreezeOutcome.STILL_VISIBLE -> stillVisible++
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to freeze $pkg", e)
                if (isResolvable(context, pkg)) {
                    stillVisible++
                }
            }
        }

        fun logResult(total: Int) {
            Log.i(
                TAG,
                "newly frozen $newlyFrozen, reconciled $reconciled, already hidden $alreadyHidden, " +
                    "still visible $stillVisible, of $total packages"
            )
        }
    }

    private fun stopFreezeService(context: Context) {
        try {
            context.stopService(Intent(context, FreezeService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Packages in [list] that PackageManager can still resolve (not actually hidden). */
    fun countStillVisible(context: Context, list: Array<String>): Int {
        if (!AntiSpyManager.isWorkProfile(context)) {
            return 0
        }
        return Utility.normalizeStringList(list).count { pkg ->
            pkg.isNotEmpty() && isResolvable(context, pkg)
        }
    }

    private fun freezePackage(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        pkg: String,
    ): FreezeOutcome {
        if (!isResolvable(context, pkg)) {
            return FreezeOutcome.ALREADY
        }
        // Best-effort: скрытие пакета не останавливает уже запущенный процесс
        // (платформа, 4PDA #2091). Фоновые процессы добиваем заранее; foreground-
        // сервисы (музыка в фоне) этот вызов не трогает -- это честная граница.
        try {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "killBackgroundProcesses($pkg) failed", e)
        }
        if (dpm.isApplicationHidden(admin, pkg)) {
            Log.w(TAG, "desync: $pkg hidden in DPM but visible in PM, re-applying")
            return if (reconcileHide(dpm, admin, context, pkg)) {
                FreezeOutcome.RECONCILED
            } else {
                FreezeOutcome.STILL_VISIBLE
            }
        }
        if (dpm.setApplicationHidden(admin, pkg, true) && !isResolvable(context, pkg)) {
            return FreezeOutcome.NEWLY
        }
        Log.w(TAG, "hide failed or incomplete for foreground $pkg, toggling hidden")
        return if (reconcileHide(dpm, admin, context, pkg)) {
            FreezeOutcome.RECONCILED
        } else {
            FreezeOutcome.STILL_VISIBLE
        }
    }

    private fun reconcileHide(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        pkg: String,
    ): Boolean {
        dpm.setApplicationHidden(admin, pkg, false)
        val ok = dpm.setApplicationHidden(admin, pkg, true)
        if (!ok || isResolvable(context, pkg)) {
            Log.w(TAG, "reconcile incomplete for $pkg (dpm=$ok, pm visible=${isResolvable(context, pkg)})")
            return false
        }
        return true
    }

    /**
     * True if the package is currently visible to PackageManager in this (work) profile.
     * A genuinely hidden (frozen) app behaves like uninstalled and throws NameNotFoundException,
     * so a successful lookup means DPM's hidden flag has not actually taken effect.
     */
    private fun isResolvable(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Same list as the snowflake button; delegates to [AntiSpyManager.getAutoFreezeList]. */
    fun freezeAutoFreezeList(context: Context): Int =
        freezeList(context, AntiSpyManager.getAutoFreezeList())

    /**
     * Что морозит владелец триггера блокировки экрана (FreezeService) по выбранной
     * области. Системные приложения профиля не трогаются ни при каком выборе: без них
     * профиль перестает работать, а противник по модели угроз -- стороннее приложение
     * (`docs/threat_model.md`).
     */
    fun packagesForScreenLockScope(
        context: Context,
        scope: ScreenLockFreezeScope,
    ): Array<String> =
        when (scope) {
            ScreenLockFreezeScope.SESSION -> emptyArray()
            ScreenLockFreezeScope.AUTO_FREEZE_LIST -> AntiSpyManager.getAutoFreezeList(context)
            ScreenLockFreezeScope.WHOLE_WORK_PROFILE -> thirdPartyPackages(context)
        }

    /**
     * Что морозить по выбранной области. Системные приложения профиля не трогаются ни при
     * каком выборе: без них профиль перестает работать, а противник по модели угроз --
     * стороннее приложение (`docs/threat_model.md`).
     */
    fun packagesForScope(context: Context, scope: AntiSpyFreezeScope): Array<String> =
        when (scope) {
            AntiSpyFreezeScope.AUTO_FREEZE_LIST -> AntiSpyManager.getAutoFreezeList(context)
            AntiSpyFreezeScope.WHOLE_WORK_PROFILE -> thirdPartyPackages(context)
        }

    private fun thirdPartyPackages(context: Context): Array<String> {
        return try {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.packageName }
                .toTypedArray()
        } catch (e: Exception) {
            Log.w(TAG, "installed applications query failed", e)
            emptyArray()
        }
    }
}
