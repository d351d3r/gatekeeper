package io.gatekeeper.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver

/**
 * Временное снятие ограничений установки на время собственной операции Gatekeeper (F1).
 *
 * Ограничения «запрет установки», «запрет неизвестных источников» и «запрет удаления»
 * действуют и на владельца профиля: с ними клонирование и удаление клона невозможны.
 * Раньше Gatekeeper снимал их на каждом запуске реле -- и вместе с этим молча отменял
 * переключатели изоляции, которые пользователь включил. Теперь желаемое состояние живет
 * в настройках изоляции, а здесь ограничение снимается только на время операции и
 * возвращается сразу после нее ([restore]). Если процесс умрет посередине, отпечаток
 * политики сброшен заранее, и следующее же реле применит набор целиком.
 */
object InstallRestrictionLease {
    private const val TAG = "InstallRestrictionLease"

    /** Мешают установке клона (сессия PackageInstaller, enableSystemApp). */
    val INSTALL: List<String> = listOf(
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
    )

    /** Мешают удалению клона. */
    val UNINSTALL: List<String> = listOf(UserManager.DISALLOW_UNINSTALL_APPS)

    /** Какие из [keys] сейчас включены пользователем и потому требуют снятия. Без Android SDK. */
    fun keysToLift(keys: List<String>, isEnabled: (String) -> Boolean): List<String> =
        keys.filter(isEnabled)

    /** Снять на время операции те из [keys], что включены. Вне профиля -- ничего. */
    fun lift(context: Context, keys: List<String>) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isProfileOwnerApp(context.packageName)) return
        val storage = LocalStorageManager.getInstance()
        val toLift = keysToLift(keys) { storage.getBoolean(IsolationPolicies.prefName(it)) }
        if (toLift.isEmpty()) return
        WorkProfilePolicy.invalidateApplied()
        val admin = ComponentName(context.applicationContext, GatekeeperDeviceAdminReceiver::class.java)
        for (key in toLift) {
            try {
                dpm.clearUserRestriction(admin, key)
            } catch (e: SecurityException) {
                Log.w(TAG, "clear $key refused", e)
            }
        }
        Log.i(TAG, "lifted for own operation: $toLift")
    }

    /** Вернуть желаемое состояние всех ограничений изоляции. Вне профиля -- ничего. */
    fun restore(context: Context) {
        IsolationEnforcer.apply(context)
    }
}
