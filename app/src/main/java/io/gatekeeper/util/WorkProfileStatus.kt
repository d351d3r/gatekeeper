package io.gatekeeper.util

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.gatekeeper.widget.FreezeWidgetProvider

/**
 * Последнее известное состояние рабочего профиля: сколько приложений всего и
 * сколько из них заморожено.
 *
 * Плитка в шторке и виджет на столе живут в личном профиле и не могут спросить
 * рабочий напрямую: привязка к его сервису идет через реле и требует живой
 * activity. Поэтому счетчики кладет сюда главный экран каждый раз, когда получил
 * список (`MainActivity.onWorkAppsLoaded`), а поверхности читают кэш и честно
 * говорят, что показывают последнее известное.
 */
object WorkProfileStatus {

    fun store(context: Context, total: Int, frozen: Int) {
        val storage = LocalStorageManager.getInstance()
        val sameAsBefore = storage.getInt(LocalStorageManager.PREF_WORK_APPS_TOTAL) == total &&
            storage.getInt(LocalStorageManager.PREF_WORK_APPS_FROZEN) == frozen
        storage.setInt(LocalStorageManager.PREF_WORK_APPS_TOTAL, total)
        storage.setInt(LocalStorageManager.PREF_WORK_APPS_FROZEN, frozen)
        if (!sameAsBefore) {
            notifySurfaces(context)
        }
    }

    /** total = 0 означает «ещё ни разу не видели список», а не «приложений нет». */
    fun read(): Counts {
        val storage = LocalStorageManager.getInstance()
        val total = storage.getInt(LocalStorageManager.PREF_WORK_APPS_TOTAL)
        val frozen = storage.getInt(LocalStorageManager.PREF_WORK_APPS_FROZEN)
        return if (total == Int.MIN_VALUE || frozen == Int.MIN_VALUE) {
            Counts(0, 0)
        } else {
            Counts(total, frozen)
        }
    }

    /**
     * Действие, которое поверхность предлагает следующим. Осталось хоть одно
     * работающее -- морозим: это сценарий «заморозить все перед тем, как дать
     * телефон». Разморозка предлагается, только когда заморожено уже все.
     */
    fun nextActionIsFreeze(counts: Counts): Boolean = counts.running > 0

    fun notifySurfaces(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val provider = ComponentName(app, FreezeWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(provider)
        if (ids.isEmpty()) {
            return
        }
        app.sendBroadcast(
            Intent(app, FreezeWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
        )
    }

    data class Counts(val total: Int, val frozen: Int) {
        val running: Int get() = total - frozen
        val known: Boolean get() = total > 0
    }
}
