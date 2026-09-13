package io.gatekeeper.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.appcompat.app.AlertDialog
import io.gatekeeper.R
import io.gatekeeper.util.AntiSpyLaunchGate
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager
import io.gatekeeper.util.Utility

/**
 * Anti Spy launch path: гейт VPN через псевдо-VpnService циклы и переброс запуска
 * в рабочий профиль. Вынесен из DummyActivity -- без своего состояния, живёт,
 * пока жив экран действия. Рекурсия согласований сохранена как в оригинале:
 * `onPermissionRestart` по умолчанию повторяет тот же гейт.
 */
class AntiSpyLaunchFlow(private val activity: Activity) {

    /** Разрешение VpnService уже есть или не нужно -- можно идти дальше. */
    fun ensureVpnPermissionThenLaunch(): Boolean {
        val storage = LocalStorageManager.getInstance()
        val needsClear = AntiSpyLaunchGate.needsVpnClear(activity, storage)
        val prepare = if (needsClear) VpnService.prepare(activity) else null
        if (prepare != null) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(prepare, DummyActivity.REQUEST_ANTI_SPY_VPN)
            return false
        }
        return true
    }

    /**
     * Гейт перед запуском: снимает VPN псевдоциклами и только потом зовёт
     * [onProceed]. Повтор при получении разрешения -- через [onPermissionRestart]
     * (по умолчанию тот же гейт заново, как раньше делал runAntiSpyLaunchGate).
     */
    fun runGate(
        packageName: String,
        onProceed: () -> Unit,
        onPermissionRestart: () -> Unit = { runGate(packageName, onProceed) },
    ) {
        AntiSpyLaunchGate.runBeforeLaunch(
            activity, LocalStorageManager.getInstance(), packageName,
            { onProceed() },
            { reason ->
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    if (ensureVpnPermissionThenLaunch()) {
                        onPermissionRestart()
                    }
                    return@runBeforeLaunch
                }
                activity.finish()
            }
        )
    }

    /** Диалог отказа в разрешении VPN; нейтральная кнопка даёт второй шанс через [onRestart]. */
    fun showPermissionDeniedDialog(onRestart: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.anti_spy_vpn_block_title)
            .setMessage(R.string.anti_spy_vpn_permission_required)
            .setPositiveButton(android.R.string.ok) { _, _ -> activity.finish() }
            .setNeutralButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                if (ensureVpnPermissionThenLaunch()) {
                    onRestart()
                }
            }
            .setOnCancelListener { activity.finish() }
            .show()
    }

    /** Personal -> work: разморозить пакет(ы), запустить, заморозить обратно по настройке. */
    fun forwardUnfreezeAndLaunchToWorkProfile() {
        val forwardIntent = Intent(DummyActivity.UNFREEZE_AND_LAUNCH)
        if (!Utility.tryTransferIntentToProfile(activity, forwardIntent)) return
        val intent = activity.intent
        val packageName = requireNotNull(intent.getStringExtra("packageName"))
        forwardIntent.putExtra("packageName", packageName)
        forwardIntent.putExtra(
            "shouldFreeze",
            SettingsManager.getInstance().getAutoFreezeServiceEnabled() &&
                LocalStorageManager.getInstance()
                    .stringListContains(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName
                    )
        )
        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
            val packagesShouldFreeze = BooleanArray(packages.size)
            for (i in packages.indices) {
                packagesShouldFreeze[i] = SettingsManager.getInstance()
                    .getAutoFreezeServiceEnabled() &&
                    LocalStorageManager.getInstance()
                        .stringListContains(
                            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                            packages[i]
                        )
            }
            forwardIntent.putExtra("linkedPackages", packages)
            forwardIntent.putExtra("linkedPackagesShouldFreeze", packagesShouldFreeze)
        }
        activity.startActivity(forwardIntent)
    }

    /** Personal -> work: просто разморозить пакет. */
    fun forwardUnfreezeAppToWorkProfile(packageName: String) {
        val forwardIntent = Intent(DummyActivity.UNFREEZE_APP)
        if (!Utility.tryTransferIntentToProfile(activity, forwardIntent)) return
        forwardIntent.putExtra("packageName", packageName)
        activity.startActivity(forwardIntent)
    }
}
