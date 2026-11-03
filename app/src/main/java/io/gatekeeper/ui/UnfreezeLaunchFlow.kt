package io.gatekeeper.ui

import android.content.Intent
import io.gatekeeper.R
import io.gatekeeper.services.FreezeService
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.ProfileNotifier

/**
 * Релей «разморозить и запустить»: UNFREEZE_AND_LAUNCH и
 * PUBLIC_UNFREEZE_AND_LAUNCH. Личная сторона гейтит запуск anti-spy (VPN-циклы) и перебрасывает
 * действие в рабочий профиль; рабочая сторона (профиль-оунер) имеет DPM-привилегии
 * и исполняет разморозку + запуск + регистрацию обратной заморозки. Вынесено
 * из DummyActivity.
 */
class UnfreezeLaunchFlow(private val activity: DummyActivity) {
    private val antiSpyFlow: AntiSpyLaunchFlow
        get() = activity.antiSpyFlowInternal
    private val isProfileOwner: Boolean
        get() = activity.isProfileOwnerInternal
    private val intent
        get() = activity.intent

    fun handleUnfreezeAndLaunch() {
        if (!isProfileOwner) {
            unfreezeAndLaunchFromPersonal()
            return
        }
        // Ярлыки «Разморозить и запустить» создает личная копия, и их интент адресован
        // личной DummyActivity. В профиле публичное действие без подписи может прислать
        // только приложение профиля -- раньше оно так размораживало любые пакеты, включая
        // linkedPackages, мимо шлюза Anti Spy (пересылка в личный профиль падала: фильтра
        // для этого действия нет, и выполнение доходило до разморозки здесь). S2.
        if (DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH == intent.action) {
            android.util.Log.w("UnfreezeLaunchFlow", "public unfreeze refused in the work profile")
            activity.finish()
            return
        }
        unfreezeAndLaunchAsProfileOwner()
    }

    /** Личная сторона: гейт anti-spy, затем форвард в рабочий профиль. */
    private fun unfreezeAndLaunchFromPersonal() {
        if (intent.getStringExtra("packageName") == null) {
            activity.finish()
            return
        }
        if (!antiSpyFlow.ensureVpnPermissionThenLaunch()) {
            return
        }
        val proceed = {
            antiSpyFlow.forwardUnfreezeAndLaunchToWorkProfile()
            activity.finish()
        }
        val packageName = requireNotNull(intent.getStringExtra("packageName"))
        if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
            restartAntiSpyGate()
        } else {
            proceed()
        }
    }

    /** Рабочая сторона: разморозить linked-пакеты и цель, запустить, по настройке заморозить обратно. */
    private fun unfreezeAndLaunchAsProfileOwner() {
        unfreezeLinkedPackages()
        val packageName = intent.getStringExtra("packageName")!!
        activity.policyManagerInternal.setApplicationHidden(
            activity.adminComponent(),
            packageName,
            false,
        )
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            if (intent.getBooleanExtra("shouldFreeze", false)) {
                registerAppToFreeze(packageName)
            }
            activity.startActivity(launchIntent)
        } else {
            GatekeeperToast.show(
                activity,
                activity.getString(R.string.launch_app_fail, packageName),
            )
        }
        activity.finish()
    }

    private fun unfreezeLinkedPackages() {
        if (!intent.hasExtra("linkedPackages")) {
            return
        }
        val packages = intent.getStringArrayExtra("linkedPackages")!!
        val packagesShouldFreeze = intent.getBooleanArrayExtra("linkedPackagesShouldFreeze")!!
        for (i in packages.indices) {
            activity.policyManagerInternal.setApplicationHidden(
                activity.adminComponent(),
                packages[i],
                false,
            )
            if (packagesShouldFreeze[i]) {
                registerAppToFreeze(packages[i])
            }
        }
    }

    private fun restartAntiSpyGate() {
        antiSpyFlow.runGate(
            requireNotNull(intent.getStringExtra("packageName")),
            {
                antiSpyFlow.forwardUnfreezeAndLaunchToWorkProfile()
                activity.finish()
            },
        )
    }

    private fun registerAppToFreeze(packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        activity.startService(Intent(activity, FreezeService::class.java))
    }
}
