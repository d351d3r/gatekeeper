package io.gatekeeper.ui

import android.content.Intent
import io.gatekeeper.R
import io.gatekeeper.services.FreezeService
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.AntiSpyVpnGuard
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.ProfileNotifier

/**
 * Релей «разморозить и запустить»: UNFREEZE_AND_LAUNCH / PUBLIC_UNFREEZE_AND_LAUNCH
 * и UNFREEZE_APP. Личная сторона гейтит запуск anti-spy (VPN-циклы) и перебрасывает
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
        if (tryForwardPublicToParent()) {
            return
        }
        if (!isProfileOwner) {
            unfreezeAndLaunchFromPersonal()
            return
        }
        unfreezeAndLaunchAsProfileOwner()
    }

    fun handleUnfreezeApp() {
        if (!isProfileOwner) {
            unfreezeAppFromPersonal()
            return
        }
        unfreezeAppAsProfileOwner()
    }

    /** Рабочая сторона, публичный шорткат: перебросить обратно в личный профиль. */
    private fun tryForwardPublicToParent(): Boolean {
        val forwarded = isProfileOwner &&
            DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH == intent.action &&
            AntiSpyVpnGuard.forwardPublicUnfreezeToParent(activity, intent)
        if (forwarded) {
            activity.finish()
        }
        return forwarded
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

    /** Личная сторона UNFREEZE_APP: гейт anti-spy, затем форвард в рабочий профиль. */
    private fun unfreezeAppFromPersonal() {
        val packageName = intent.getStringExtra("packageName") ?: run {
            activity.finish()
            return
        }
        if (!antiSpyFlow.ensureVpnPermissionThenLaunch()) {
            return
        }
        val proceed = {
            antiSpyFlow.forwardUnfreezeAppToWorkProfile(packageName)
            activity.finish()
        }
        if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
            antiSpyFlow.runGate(packageName, proceed) { handleUnfreezeApp() }
        } else {
            proceed()
        }
    }

    /** Рабочая сторона UNFREEZE_APP: просто снять скрытие и обновить список. */
    private fun unfreezeAppAsProfileOwner() {
        val packageName = intent.getStringExtra("packageName") ?: run {
            activity.finish()
            return
        }
        activity.policyManagerInternal.setApplicationHidden(
            activity.adminComponent(),
            packageName,
            false,
        )
        ProfileNotifier.scheduleAppListRefresh(activity)
        activity.finish()
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
