package io.gatekeeper.util

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.MainActivity

/**
 * Уведомления: каналы (создание один раз, выбор пользователя не трогаем),
 * статусные нотификации сервисов и алерты пользователю. Вынесено из Utility.
 */
object Notifications {

    /** Строка, которой Android обязывает сервис переднего плана. Скрыть ее нельзя. */
    const val CHANNEL_STATUS = "gatekeeper.status"

    /** Заморозка: взведенный автомат по блокировке экрана и отчеты о заморозке. */
    const val CHANNEL_FREEZE = "gatekeeper.freeze"

    /** Реакция на VPN: отсчет до заморозки, отмена, исход. */
    const val CHANNEL_ANTISPY = "gatekeeper.antispy"

    /** Настройка профиля: шаг, который должен доделать пользователь. */
    const val CHANNEL_SETUP = "gatekeeper.setup"

    /** Проблемы: приложение не смогло сделать то, о чем его просили. */
    const val CHANNEL_WARNING = "gatekeeper.warning"

    private const val VPN_AUTO_FREEZE_SUCCESS_NOTIFICATION_ID = 0xe49d3
    private const val REQUEST_OPEN_MAIN = 0x6d61

    /**
     * Каналы первых версий. Имя и описание канала Android берет только при создании,
     * поэтому переименовать их нельзя -- только удалить и создать новые.
     */
    private val LEGACY_CHANNELS = listOf(
        "GatekeeperService",
        "GatekeeperService-Important",
        "GatekeeperUserAlerts",
    )

    private class ChannelSpec(
        @StringRes val nameRes: Int,
        @StringRes val descRes: Int,
        val importance: Int,
    )

    private val CHANNELS: Map<String, ChannelSpec> = mapOf(
        CHANNEL_STATUS to ChannelSpec(
            R.string.notif_channel_status,
            R.string.notif_channel_status_desc,
            NotificationManager.IMPORTANCE_MIN,
        ),
        CHANNEL_FREEZE to ChannelSpec(
            R.string.notif_channel_freeze,
            R.string.notif_channel_freeze_desc,
            NotificationManager.IMPORTANCE_LOW,
        ),
        CHANNEL_ANTISPY to ChannelSpec(
            R.string.notif_channel_antispy,
            R.string.notif_channel_antispy_desc,
            NotificationManager.IMPORTANCE_HIGH,
        ),
        CHANNEL_SETUP to ChannelSpec(
            R.string.notif_channel_setup,
            R.string.notif_channel_setup_desc,
            NotificationManager.IMPORTANCE_HIGH,
        ),
        CHANNEL_WARNING to ChannelSpec(
            R.string.notif_channel_warning,
            R.string.notif_channel_warning_desc,
            NotificationManager.IMPORTANCE_HIGH,
        ),
    )

    @Volatile
    private var legacyChannelsDropped = false

    fun postUserAlert(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        channel: String = CHANNEL_ANTISPY,
    ) {
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java).notify(
            notificationId,
            buildUserAlertNotification(app, title, text, channel),
        )
    }

    fun postVpnAutoFreezeSuccessAlert(context: Context) {
        val app = context.applicationContext
        postUserAlert(
            app,
            VPN_AUTO_FREEZE_SUCCESS_NOTIFICATION_ID,
            app.getString(R.string.anti_spy_monitor_notification_title),
            app.getString(R.string.freeze_all_success),
            CHANNEL_FREEZE,
        )
    }

    fun buildUserAlertNotification(
        context: Context,
        title: String,
        text: String,
        channel: String = CHANNEL_ANTISPY,
        @DrawableRes icon: Int = R.drawable.ic_lock_open,
    ): Notification = newBuilder(context.applicationContext, channel)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setSmallIcon(icon)
        .setContentIntent(openMainAppIntent(context))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_STATUS)
        .build()

    fun buildNotification(
        context: Context,
        content: StatusNotification,
    ): Notification = newBuilder(context.applicationContext, content.channel)
        .setTicker(content.ticker)
        .setContentTitle(content.title)
        .setContentText(content.desc)
        .setStyle(Notification.BigTextStyle().bigText(content.desc))
        .setSmallIcon(content.icon)
        .setContentIntent(openMainAppIntent(context))
        .build()

    /**
     * Куда ведет нажатие на любое уведомление. В рабочем профиле MainActivity выключена
     * (см. WorkProfilePolicy), поэтому оттуда главный экран личного профиля открывается
     * через системный форвардер.
     */
    fun openMainAppIntent(context: Context): PendingIntent? {
        val app = context.applicationContext
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent: Intent?
        if (Utility.isProfileOwner(app)) {
            // Без подписи: она живет 30 секунд, а PendingIntent уведомления -- пока висит
            // уведомление. Действие умеет ровно одно -- открыть наш же главный экран.
            val relay = Intent(DummyActivity.OPEN_MAIN_APP)
            intent = if (Utility.tryTransferIntentToProfileUnsigned(app, relay)) relay else null
        } else {
            intent = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent?.let { PendingIntent.getActivity(app, REQUEST_OPEN_MAIN, it, flags) }
    }

    @Suppress("DEPRECATION")
    private fun newBuilder(context: Context, channel: String): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel(context, channel)
            return Notification.Builder(context, channel)
        }
        val loud = isLoud(channel)
        return Notification.Builder(context)
            .setPriority(if (loud) Notification.PRIORITY_MAX else Notification.PRIORITY_MIN)
    }

    private fun isLoud(channel: String): Boolean =
        spec(channel).importance >= NotificationManager.IMPORTANCE_DEFAULT

    private fun spec(channel: String): ChannelSpec =
        CHANNELS[channel] ?: CHANNELS.getValue(CHANNEL_STATUS)

    /** Завести все каналы разом, чтобы они были видны в системных настройках до первого показа. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val app = context.applicationContext
        for (channel in CHANNELS.keys) {
            ensureChannel(app, channel)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun ensureChannel(context: Context, channel: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!legacyChannelsDropped) {
            legacyChannelsDropped = true
            for (legacy in LEGACY_CHANNELS) {
                nm.deleteNotificationChannel(legacy)
            }
        }
        // Канал настраивается только при создании: перебивать выбор пользователя из кода
        // недопустимо, и Android этого все равно не дает.
        if (nm.getNotificationChannel(channel) != null) {
            return
        }
        val spec = spec(channel)
        val chan = NotificationChannel(channel, context.getString(spec.nameRes), spec.importance)
        chan.description = context.getString(spec.descRes)
        chan.enableVibration(isLoud(channel))
        chan.enableLights(isLoud(channel))
        nm.createNotificationChannel(chan)
    }
}

/** Содержимое статусной нотификации сервиса. */
data class StatusNotification(
    val ticker: String,
    val title: String,
    val desc: String,
    @DrawableRes val icon: Int,
    val channel: String = Notifications.CHANNEL_STATUS,
)
