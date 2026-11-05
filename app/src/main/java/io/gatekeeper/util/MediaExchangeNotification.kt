package io.gatekeeper.util

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import io.gatekeeper.R

/** One quiet foreground notification, with detailed changing status kept in settings. */
object MediaExchangeNotification {
    fun build(context: Context): Notification {
        Notifications.ensureChannels(context)
        return NotificationCompat.Builder(context.applicationContext, Notifications.CHANNEL_MEDIA)
            .setContentTitle(context.getString(R.string.media_exchange_title))
            .setContentText(context.getString(R.string.media_exchange_notification))
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
