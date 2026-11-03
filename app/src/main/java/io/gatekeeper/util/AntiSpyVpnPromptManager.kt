package io.gatekeeper.util

import android.content.Context
import android.content.Intent
import android.util.Log
import io.gatekeeper.ui.AntiSpyVpnPromptActivity
import io.gatekeeper.ui.DummyActivity
import java.util.concurrent.atomic.AtomicBoolean

import io.gatekeeper.util.ProfileNotifier
/**
 * Shows Anti Spy VPN dialogs on the home screen (Activity), not in the notification shade.
 */
object AntiSpyVpnPromptManager {
    private const val TAG = "AntiSpyVpnPrompt"

    const val EXTRA_MODE = "anti_spy_vpn_prompt_mode"
    const val MODE_FREEZE_PROMPT = 1
    const val MODE_VPN_PERMISSION = 2
    const val MODE_DISPLACEMENT_FAILED = 3

    private val freezePromptActive = AtomicBoolean(false)
    @Volatile
    private var declinedForCurrentVpnSession = false

    fun isPromptActive(): Boolean = freezePromptActive.get()

    fun isDeclinedForCurrentVpnSession(): Boolean = declinedForCurrentVpnSession

    fun onVpnSessionEnded() {
        declinedForCurrentVpnSession = false
    }

    /** Freeze Yes/No — only after external VPN was displaced. */
    fun showPrompt(context: Context) {
        val app = context.applicationContext
        if (freezePromptActive.getAndSet(true)) {
            return
        }
        if (VpnTunnelDetector.isVpnActive(app)) {
            Log.w(TAG, "showPrompt refused: vpn still active")
            freezePromptActive.set(false)
            return
        }
        launchScreenDialog(context, MODE_FREEZE_PROMPT)
    }

    fun showVpnPermissionNeeded(context: Context) {
        launchScreenDialog(context, MODE_VPN_PERMISSION)
    }

    fun showDisplacementFailed(context: Context) {
        launchScreenDialog(context, MODE_DISPLACEMENT_FAILED)
    }

    private fun launchScreenDialog(context: Context, mode: Int) {
        val app = context.applicationContext
        val intent = Intent(app, AntiSpyVpnPromptActivity::class.java)
        intent.putExtra(EXTRA_MODE, mode)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )

        try {
            // Это прямой startActivity, а не отправка PendingIntent: опция
            // setPendingIntentBackgroundActivityStartMode здесь ни на что не влияла и
            // убрана. Из фона экран открывается только благодаря исключению BAL за
            // разрешение «Поверх других окон»; без него система тихо отклонит запуск
            // (docs/audit_android17.md, A2), и об этом предупреждает экран «Доступы».
            app.startActivity(intent)
            Log.i(TAG, "screen dialog launched mode=$mode")
        } catch (e: Exception) {
            Log.e(TAG, "launchScreenDialog failed mode=$mode", e)
            if (mode == MODE_FREEZE_PROMPT) {
                freezePromptActive.set(false)
            }
        }
    }

    fun dismiss(@Suppress("UNUSED_PARAMETER") context: Context) {
        freezePromptActive.set(false)
    }

    fun onUserDeclined(@Suppress("UNUSED_PARAMETER") context: Context) {
        declinedForCurrentVpnSession = true
        dismiss(context)
    }

    fun onUserConfirmedFreeze(context: Context) {
        dismiss(context)
        val intent = Intent(context, DummyActivity::class.java)
        intent.action = DummyActivity.PUBLIC_FREEZE_ALL
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = android.content.ComponentName(context, DummyActivity::class.java)
        AuthenticationUtility.signIntent(intent)
        context.startActivity(intent)
        ProfileNotifier.scheduleAppListRefresh(context)
    }
}
