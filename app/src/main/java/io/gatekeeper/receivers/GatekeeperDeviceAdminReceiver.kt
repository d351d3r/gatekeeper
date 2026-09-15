package io.gatekeeper.receivers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.StatusNotification
import io.gatekeeper.util.Utility
import io.gatekeeper.util.WorkProfilePolicy

class GatekeeperDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        // setProfileEnabled в этом broadcast, а не только в FinalizeActivity: на
        // Android 8+ единственным путём к включению профиля была activity-цепочка
        // (FinalizeActivity -> DummyActivity -> enforceWorkProfilePolicies), а её
        // система убивает ресайклом процесса сразу после установки DPC в профиль
        // (замер на AVD 16: "Killing io.gatekeeper/u15 (change io.gatekeeper)" через
        // 10 мс после запуска, до setProfileEnabled). Профиль оставался DISABLED без
        // восстановления. Этот callback доставляется системой синхронно и надёжно --
        // включаем профиль здесь, а полную настройку доделает activity/реле, когда
        // приложение в профиле сможет запуститься.
        runCatching {
            val manager = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(
                context.applicationContext,
                GatekeeperDeviceAdminReceiver::class.java,
            )
            manager?.setProfileEnabled(admin)
        }.onFailure { Log.w(TAG, "setProfileEnabled on provisioning complete failed", it) }

        // Кросс-профильные фильтры реле регистрируем здесь же, а не только в
        // activity-цепочке: её убивает тот же ресайкл процесса ("change io.gatekeeper"),
        // и без фильтров личная сторона не резолвит TRY_START_SERVICE ("no system
        // forwarder") -- экран завершения висит вечно, хотя профиль уже включён.
        // Этот callback доставляется надёжно, поэтому реле поднимается до убийства.
        runCatching {
            WorkProfilePolicy.enforceWorkProfilePolicies(context.applicationContext)
        }.onFailure { Log.w(TAG, "enforceWorkProfilePolicies on provisioning complete failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        val i = Intent(context.applicationContext, DummyActivity::class.java)
        i.action = DummyActivity.FINALIZE_PROVISION
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val notification = Notifications.buildNotification(
            context,
            StatusNotification(
                "gatekeeper-finish-provision",
                context.getString(R.string.finish_provision_title),
                context.getString(R.string.finish_provision_desc),
                R.drawable.ic_notification,
                Notifications.CHANNEL_SETUP,
            ),
        )
        notification.contentIntent = PendingIntent.getActivity(
            context,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 114514
        private const val TAG = "GatekeeperDPC"
    }
}
