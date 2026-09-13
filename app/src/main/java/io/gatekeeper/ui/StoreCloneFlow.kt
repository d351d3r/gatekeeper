package io.gatekeeper.ui

import android.app.Activity
import android.os.RemoteException
import androidx.appcompat.app.AlertDialog
import io.gatekeeper.R
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.services.IGetAppsCallback
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.StoreCloneHint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Одноразовая подсказка после настройки: магазина приложений нет в рабочем
 * профиле -- предлагаем клонировать одним тапом. Закрывает самый частый
 * вопрос новичков (4PDA #2097-#2118, #2100). Чистая логика порогов -- в
 * [StoreCloneHint]; здесь только binder-вызовы и UI. Вынесено из MainActivity.
 */
class StoreCloneFlow(private val activity: MainActivity) {

    /**
     * @return true, если диалог будет показан (подсказки не наслаиваются).
     */
    fun maybePrompt(
        main: IGatekeeperService?,
        work: IGatekeeperService?,
    ): Boolean {
        if (main == null || work == null) return false
        return promptIfDue(main, work)
    }

    private fun promptIfDue(
        main: IGatekeeperService,
        work: IGatekeeperService,
    ): Boolean {
        val local = LocalStorageManager.getInstance()
        if (!StoreCloneHint.isDue(
                local.getIntFresh(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                    StoreCloneHint.STATE_NEVER_ASKED,
                ),
                local.getLong(LocalStorageManager.PREF_STORE_CLONE_HINT_SNOOZE_AT, 0L),
                System.currentTimeMillis(),
            )
        ) return false
        Thread {
            val (store, action) = findCloneableStore(main, work) ?: return@Thread
            activity.window.decorView.post {
                if (activity.isFinishing) return@post
                // Фиксируем показ сразу: повторный bind после поворота экрана
                // не должен вызывать диалог повторно. "Позже" snooze на неделю.
                local.setInt(LocalStorageManager.PREF_STORE_CLONE_HINT_STATE, StoreCloneHint.STATE_SNOOZED)
                local.setLong(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_SNOOZE_AT,
                    System.currentTimeMillis(),
                )
                showDialog(store, action)
            }
        }.start()
        return true
    }

    /**
     * Кого и что предложить: см. [StoreCloneHint.pickAction]. Доступность в
     * рабочем профиле считаем по факту (FLAG_INSTALLED + launcher-активти),
     * а не по вхождению в showAll-выдачу: в неё попадают системные пакеты из
     * образа прошивки с неснятым FLAG_INSTALLED, из-за чего подсказка на
     * GMS-устройствах не срабатывала никогда (C1).
     */
    private fun findCloneableStore(
        main: IGatekeeperService,
        work: IGatekeeperService,
    ): Pair<ApplicationInfoWrapper, Int>? {
        val mainApps = fetchApps(main)
        val workApps = fetchApps(work)
        return if (mainApps == null || workApps == null) {
            null
        } else {
            pickCloneableStore(mainApps, workApps)
        }
    }

    private fun pickCloneableStore(
        mainApps: List<ApplicationInfoWrapper>,
        workApps: List<ApplicationInfoWrapper>,
    ): Pair<ApplicationInfoWrapper, Int>? {
        val mainHasApk = mainApps.associate { wrapper ->
            wrapper.getPackageName() to (wrapper.isInstalled() && !wrapper.getSourceDir().isNullOrBlank())
        }
        val workStates = workApps.associate { wrapper ->
            wrapper.getPackageName() to StoreCloneHint.classifyWorkState(
                wrapper.isInstalled(),
                wrapper.isHidden(),
                wrapper.canLaunch(),
            )
        }
        val (pkg, action) = StoreCloneHint.pickAction(mainHasApk, workStates) ?: return null
        return mainApps.firstOrNull { it.getPackageName() == pkg }?.let { store -> store to action }
    }

    private fun fetchApps(service: IGatekeeperService): List<ApplicationInfoWrapper>? {
        val latch = CountDownLatch(1)
        var result: List<ApplicationInfoWrapper>? = null
        try {
            service.getApps(object : IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<ApplicationInfoWrapper>) {
                    result = apps
                    latch.countDown()
                }
            }, true)
        } catch (_: RemoteException) {
            return null
        }
        latch.await(STORE_LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS)
        return result
    }

    private fun showDialog(store: ApplicationInfoWrapper, action: Int) {
        val label = store.getLabel() ?: store.getPackageName()
        val title = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            R.string.store_unfreeze_hint_title
        } else {
            R.string.store_clone_hint_title
        }
        val message = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            activity.getString(R.string.store_unfreeze_hint_message, label)
        } else {
            activity.getString(R.string.store_clone_hint_message, label)
        }
        val positiveLabel = if (action == StoreCloneHint.ACTION_UNFREEZE) {
            activity.getString(R.string.store_unfreeze_hint_unfreeze, label)
        } else {
            activity.getString(R.string.store_clone_hint_clone, label)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ ->
                if (action == StoreCloneHint.ACTION_UNFREEZE) {
                    unfreezeStoreInWork(store)
                } else {
                    cloneStoreIntoWork(store)
                }
            }
            .setNegativeButton(R.string.store_clone_hint_later, null)
            .setNeutralButton(R.string.store_clone_hint_never) { _, _ ->
                LocalStorageManager.getInstance()
                    .setInt(LocalStorageManager.PREF_STORE_CLONE_HINT_STATE, StoreCloneHint.STATE_NEVER)
            }
            .show()
    }

    /**
     * Магазин в рабочем профиле установлен, но заморожен: предложение не
     * клонировать, а разморозить. Идем биндером рабочего сервиса, а не реле:
     * у UNFREEZE_APP нет кросс-профильного фильтра в enforceWorkProfilePolicies,
     * поэтому системный форвардер его не резолвит и transferIntentToProfile
     * всегда отказывает. Диалог показывается только при живом serviceWork,
     * так что биндер здесь уже есть.
     */
    private fun unfreezeStoreInWork(store: ApplicationInfoWrapper) {
        val work = activity.workServiceOrNull() ?: run {
            GatekeeperToast.show(activity, activity.getString(R.string.clone_fail_no_connection))
            return
        }
        Thread {
            val unfrozen = runCatching { work.unfreezeApp(store) }.isSuccess
            activity.window.decorView.post {
                if (!unfrozen) {
                    GatekeeperToast.show(activity, activity.getString(R.string.clone_fail_no_connection))
                    return@post
                }
                LocalStorageManager.getInstance().setInt(
                    LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                    StoreCloneHint.STATE_NEVER,
                )
                GatekeeperToast.show(
                    activity,
                    activity.getString(R.string.unfreeze_success, store.getLabel()),
                )
                activity.refreshAppLists()
            }
        }.start()
    }

    private fun cloneStoreIntoWork(store: ApplicationInfoWrapper) {
        val work = activity.workServiceOrNull() ?: return
        val callback = object : IAppInstallCallback.Stub() {
            override fun callback(result: Int) {
                activity.window.decorView.post {
                    if (result == Activity.RESULT_OK) {
                        LocalStorageManager.getInstance().setInt(
                            LocalStorageManager.PREF_STORE_CLONE_HINT_STATE,
                            StoreCloneHint.STATE_NEVER,
                        )
                        GatekeeperToast.show(
                            activity,
                            String.format(activity.getString(R.string.clone_success), store.getLabel()),
                        )
                    } else {
                        GatekeeperToast.show(
                            activity,
                            activity.getString(R.string.clone_fail_generic, store.getLabel(), result),
                        )
                    }
                }
            }
        }
        try {
            work.installApp(store, callback)
        } catch (_: RemoteException) {
            GatekeeperToast.show(activity, activity.getString(R.string.clone_fail_no_connection))
        }
    }

    private companion object {
        private const val STORE_LOOKUP_TIMEOUT_SEC = 5L
    }
}
