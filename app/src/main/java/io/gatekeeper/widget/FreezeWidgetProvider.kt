package io.gatekeeper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.MainActivity
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.WorkProfileStatus

/**
 * Виджет заморозки (G6). Ярлык, который был единственным способом заморозить с
 * рабочего стола, ничего не показывает: нажал и не знаешь, сработало ли. Виджет
 * показывает последнее известное состояние профиля и одну кнопку, которая делает
 * то, что сейчас имеет смысл: всё работает -- заморозить, что-то заморожено --
 * разморозить. Ничего опаснее заморозки на рабочий стол не выносится: клонирование,
 * доступы и сертификаты требуют экрана с объяснением последствий.
 */
class FreezeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val counts = WorkProfileStatus.read()
        val views = RemoteViews(context.packageName, R.layout.widget_freeze)

        if (!counts.known) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_unknown))
            views.setTextViewText(R.id.widget_counts, context.getString(R.string.widget_unknown_hint))
            views.setTextViewText(R.id.widget_action, context.getString(R.string.widget_open))
            views.setOnClickPendingIntent(R.id.widget_action, openAppIntent(context))
        } else {
            val freeze = WorkProfileStatus.nextActionIsFreeze(counts)
            views.setTextViewText(
                R.id.widget_title,
                context.getString(
                    if (counts.running > 0) R.string.widget_all_running else R.string.widget_partly_frozen
                )
            )
            views.setTextViewText(
                R.id.widget_counts,
                context.getString(R.string.status_counts, counts.total, counts.frozen, counts.running)
            )
            val listSize = LocalStorageManager.getInstance()
                .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE).size
            views.setTextViewText(
                R.id.widget_action,
                context.getString(
                    if (freeze) R.string.action_freeze_list else R.string.action_unfreeze_list,
                    listSize,
                )
            )
            views.setOnClickPendingIntent(R.id.widget_action, batchIntent(context, freeze))
        }
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }

    /**
     * Тот же публичный вход, которым пользуется закрепляемый ярлык: компонент задан
     * явно, иначе система показала бы выбор профиля вместо действия.
     */
    private fun batchIntent(context: Context, freeze: Boolean): PendingIntent {
        val action = if (freeze) DummyActivity.PUBLIC_FREEZE_ALL else DummyActivity.PUBLIC_UNFREEZE_ALL
        val intent = Intent(action).apply {
            component = ComponentName(context, DummyActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            if (freeze) REQUEST_FREEZE else REQUEST_UNFREEZE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_FREEZE = 0x7f01
        const val REQUEST_UNFREEZE = 0x7f02
        const val REQUEST_OPEN = 0x7f03
    }
}
