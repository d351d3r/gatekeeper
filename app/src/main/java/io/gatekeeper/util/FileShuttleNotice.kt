package io.gatekeeper.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import io.gatekeeper.R

/**
 * Выход для файловых менеджеров.
 *
 * Связь с другим профилем поднимает видимая [io.gatekeeper.ui.FileShuttleAuthActivity]:
 * запуск activity прямо из провайдера -- фоновый старт, который система рубит. Системный
 * DocumentsUI это умеет обойти -- он получает действие внутри
 * [android.app.AuthenticationRequiredException] и рисует кнопку «Войти». Любому другому
 * клиенту SAF (MiXplorer, Total Commander) то же исключение достается как обычный
 * SecurityException: показать он его может, сделать с ним -- ничего.
 *
 * Поэтому такому клиенту связь предлагается уведомлением с тем же действием: пользователь
 * тапает его, связь поднимается, и повтор в файловом менеджере уже проходит.
 */
object FileShuttleNotice {

    private const val NOTIFICATION_ID = 0xF11E0

    /**
     * Предложить подключение, если зовет не тот, кто покажет действие сам. Признак --
     * право MANAGE_DOCUMENTS: оно есть у системного выбора файлов и нет у обычного
     * приложения.
     */
    fun offerConnection(context: Context, callingPackage: String?) {
        val app = context.applicationContext
        if (callingPackage == null || showsActionItself(app, callingPackage)) return
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(app))
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    private fun build(app: Context): Notification {
        val text = app.getString(R.string.file_shuttle_notice_text)
        return Notifications.newBuilder(app, Notifications.CHANNEL_FILES)
            .setContentTitle(app.getString(R.string.file_shuttle_notice_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_upload)
            .setContentIntent(FileShuttleConnection.authPendingIntent(app))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    private fun showsActionItself(context: Context, callingPackage: String): Boolean =
        context.packageManager.checkPermission(
            MANAGE_DOCUMENTS,
            callingPackage,
        ) == PackageManager.PERMISSION_GRANTED

    private const val MANAGE_DOCUMENTS = "android.permission.MANAGE_DOCUMENTS"
}
