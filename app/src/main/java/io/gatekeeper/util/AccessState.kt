package io.gatekeeper.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Process

/**
 * Сколько специальных разрешений выдано и сколько их всего.
 *
 * Нужно строке настроек: раньше под «Разрешения» стояло оглавление экрана, а не
 * состояние. Проверки те же, что на самом экране, но без его UI, поэтому их видно
 * из корня настроек.
 */
object AccessState {

    class Counts(val granted: Int, val total: Int)

    fun count(context: Context): Counts {
        val checks = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }
            add(Utility.checkAllFileAccessPermission())
            add(appOpAllowed(context, AppOpsManager.OPSTR_GET_USAGE_STATS))
            add(Utility.checkSystemAlertPermission(context))
            add(VpnService.prepare(context) == null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(context.packageManager.canRequestPackageInstalls())
            }
        }
        return Counts(checks.count { it }, checks.size)
    }

    @Suppress("DEPRECATION") // checkOpNoThrow -- единственный вариант до Android 10
    private fun appOpAllowed(context: Context, op: String): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
