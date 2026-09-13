package io.gatekeeper.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.gatekeeper.R
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.WorkProfileStatus

/**
 * Плитка быстрых настроек (G6): заморозить профиль, не выходя из того приложения,
 * в котором сейчас находишься. Ярлык на рабочем столе этот сценарий не закрывает --
 * до стола еще надо добраться.
 *
 * Подпись плитки несет последнее известное состояние (WorkProfileStatus): спросить
 * рабочий профиль напрямую отсюда нельзя, привязка к его сервису идет через реле.
 */
class FreezeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val counts = WorkProfileStatus.read()
        val freeze = !counts.known || WorkProfileStatus.nextActionIsFreeze(counts)
        launch(batchIntent(freeze))
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val counts = WorkProfileStatus.read()
        val state = when {
            !counts.known -> getString(R.string.widget_unknown)
            counts.running > 0 -> getString(R.string.tile_subtitle_running, counts.running)
            else -> getString(R.string.tile_subtitle_frozen, counts.frozen)
        }
        tile.state = Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = state
        } else {
            // До Android 10 у плитки нет второй строки: состояние уезжает в надпись.
            tile.label = getString(R.string.tile_label_with_state, getString(R.string.tile_name), state)
        }
        tile.updateTile()
    }

    /**
     * С Android 14 плитке нельзя отдать голый Intent: activity запускается только
     * через PendingIntent, иначе вызов бросает исключение.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_TILE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun batchIntent(freeze: Boolean): Intent {
        val action = if (freeze) DummyActivity.PUBLIC_FREEZE_ALL else DummyActivity.PUBLIC_UNFREEZE_ALL
        return Intent(action).apply {
            component = ComponentName(this@FreezeTileService, DummyActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private companion object {
        const val REQUEST_TILE = 0x7f04
    }
}
