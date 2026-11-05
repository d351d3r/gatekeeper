package io.gatekeeper.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import io.gatekeeper.R

/** Core and media services share one foreground row per Android user. */
object BackgroundServiceNotification {
    // Preserve the original core-service ID. Android retains this notification until
    // the last foreground service using this ID stops (ActiveServices in AOSP).
    const val ID = 0x49a11
    const val LEGACY_MEDIA_ID = 0x4d4544

    fun build(context: Context): Notification {
        Notifications.ensureChannels(context)
        return NotificationCompat.Builder(context.applicationContext, Notifications.CHANNEL_STATUS)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.service_desc))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(Notifications.openMainAppIntent(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
