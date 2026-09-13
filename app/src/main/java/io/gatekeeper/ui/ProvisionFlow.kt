package io.gatekeeper.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import io.gatekeeper.R
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility

/**
 * Финализация провижининга рабочего профиля: рабочая сторона просто
 * закрывается (на pre-O личная сторона перезапускает действие сюда
 * без подписи), личная сторона снимает флаги сетапа и поднимает
 * SetupWizardActivity. Вынесено из DummyActivity.
 */
class ProvisionFlow(private val activity: DummyActivity) {

    fun handleFinalizeProvision() {
        if (activity.isProfileOwnerInternal) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val intent = Intent(DummyActivity.FINALIZE_PROVISION)
                if (Utility.tryTransferIntentToProfileUnsigned(activity, intent)) {
                    activity.startActivity(intent)
                }
            }
            activity.finish()
        } else {
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            val intent = Intent(SetupWizardActivity.ACTION_PROFILE_PROVISIONED).apply {
                component = ComponentName(activity, SetupWizardActivity::class.java)
            }
            activity.startActivity(intent)
            GatekeeperToast.show(
                activity,
                activity.getString(R.string.provision_finished),
                android.widget.Toast.LENGTH_LONG,
            )
            activity.finish()
        }
    }
}
