package io.gatekeeper.ui

import android.content.ComponentName
import android.content.Intent
import io.gatekeeper.R
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.Utility

/**
 * Batch freeze/unfreeze ярлыки и их фоновые действия: интент от лаунчера
 * приходит в MainActivity, она шлёт публичное действие в DummyActivity
 * (same-process nonce), та -- релей в рабочий профиль. Вынесено из
 * MainActivity; pending-действие дожидается живых сервисов после bind.
 */
class BatchShortcutFlow(private val activity: MainActivity) {

    private var pendingAction: String? = null

    /**
     * Обработать интент ярлыка/меню. Мутирует action в ACTION_MAIN, чтобы
     * повторное создание активности не переоткрыло действие.
     * @return true, если интент был batch-действием.
     */
    fun handleShortcutIntent(intent: Intent?): Boolean {
        val handled = when (intent?.action) {
            MainActivity.ACTION_BATCH_FREEZE_ALL,
            MainActivity.ACTION_BATCH_UNFREEZE_ALL,
            MainActivity.ACTION_REFRESH_APP_LISTS,
            -> true
            MainActivity.ACTION_SHOW_BATCH_TOAST -> true
            else -> false
        }
        if (!handled || intent == null) return false
        when (intent.action) {
            MainActivity.ACTION_BATCH_FREEZE_ALL -> {
                intent.action = Intent.ACTION_MAIN
                if (activity.servicesAlive()) freezeAll() else {
                    pendingAction = MainActivity.ACTION_BATCH_FREEZE_ALL
                }
            }
            MainActivity.ACTION_BATCH_UNFREEZE_ALL -> {
                intent.action = Intent.ACTION_MAIN
                if (activity.servicesAlive()) unfreezeAll() else {
                    pendingAction = MainActivity.ACTION_BATCH_UNFREEZE_ALL
                }
            }
            MainActivity.ACTION_SHOW_BATCH_TOAST -> {
                val resId = intent.getIntExtra(MainActivity.EXTRA_TOAST_RES_ID, 0)
                if (resId != 0) {
                    GatekeeperToast.show(activity, resId)
                }
            }
            MainActivity.ACTION_REFRESH_APP_LISTS -> {
                intent.action = Intent.ACTION_MAIN
                if (activity.servicesAlive()) activity.refreshAppLists() else {
                    pendingAction = MainActivity.ACTION_REFRESH_APP_LISTS
                }
            }
        }
        return true
    }

    /** Действие, отложенное до живых сервисов: выполнить после bind. */
    fun runPending() {
        when (pendingAction) {
            MainActivity.ACTION_BATCH_FREEZE_ALL -> freezeAll()
            MainActivity.ACTION_BATCH_UNFREEZE_ALL -> unfreezeAll()
            MainActivity.ACTION_REFRESH_APP_LISTS -> activity.refreshAppLists()
        }
        pendingAction = null
    }

    fun dispatchBackground(intent: Intent) {
        when (intent.action) {
            MainActivity.ACTION_BATCH_FREEZE_ALL -> launchDummy(DummyActivity.PUBLIC_FREEZE_ALL)
            MainActivity.ACTION_BATCH_UNFREEZE_ALL -> launchDummy(DummyActivity.PUBLIC_UNFREEZE_ALL)
            MainActivity.ACTION_SHOW_BATCH_TOAST -> {
                val resId = intent.getIntExtra(MainActivity.EXTRA_TOAST_RES_ID, 0)
                if (resId != 0) {
                    GatekeeperToast.show(activity, resId)
                }
                activity.refreshAppLists()
            }
            MainActivity.ACTION_REFRESH_APP_LISTS -> activity.refreshAppLists()
        }
    }

    fun freezeAll() {
        val batchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL).apply {
            component = ComponentName(activity, DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(batchIntent)
        activity.startActivity(batchIntent)
        activity.refreshAppLists()
    }

    fun unfreezeAll() {
        val batchIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_ALL).apply {
            component = ComponentName(activity, DummyActivity::class.java)
        }
        DummyActivity.registerSameProcessRequest(batchIntent)
        activity.startActivity(batchIntent)
        activity.refreshAppLists()
    }

    fun createShortcut(isFreeze: Boolean) {
        val launchIntent = batchShortcutIntent(
            if (isFreeze) DummyActivity.PUBLIC_FREEZE_ALL else DummyActivity.PUBLIC_UNFREEZE_ALL
        )
        Utility.createLauncherShortcut(
            activity,
            launchIntent,
            Utility.createBatchShortcutIcon(
                activity,
                if (isFreeze) R.drawable.ic_shortcut_freeze else R.drawable.ic_shortcut_unfreeze,
            ),
            if (isFreeze) "gatekeeper-freeze-all" else "gatekeeper-unfreeze-all",
            activity.getString(if (isFreeze) R.string.freeze_all_shortcut else R.string.unfreeze_all_shortcut),
        )
    }

    private fun launchDummy(action: String) {
        activity.startActivity(Intent(action).apply {
            component = ComponentName(activity, DummyActivity::class.java)
        })
    }

    private fun batchShortcutIntent(action: String): Intent =
        Intent(activity, DummyActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
