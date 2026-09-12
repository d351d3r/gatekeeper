package io.gatekeeper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.SettingsManager

/**
 * База подэкранов настроек (редизайн, .ai/ui-redesign/settings-plan.md). Держит
 * общее: биндер сервиса рабочего профиля, инсеты и пару общих помощников.
 * Биндер передаётся через arguments, чтобы экраны не зависели от intent активности-хоста.
 */
abstract class SettingsSubFragment : PreferenceFragmentCompat() {
    protected val manager = SettingsManager.getInstance()
    protected var serviceWork: IGatekeeperService? = null
        private set
    protected var serviceMain: IGatekeeperService? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceWork = IGatekeeperService.Stub.asInterface(arguments?.getBinder(ARG_PROFILE_SERVICE))
        serviceMain = IGatekeeperService.Stub.asInterface(arguments?.getBinder(ARG_MAIN_SERVICE))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(
            view.findViewById(androidx.preference.R.id.recycler_view)
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPaddingRelative(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Разовое действие "сначала системное разрешение, потом включаем": если доступа
     * нет, показываем объяснение с кнопкой в нужный системный экран и говорим
     * вызывающему коду не применять переключатель.
     */
    protected fun ensureSpecialAccessPermission(
        checkPermission: () -> Boolean,
        alertRes: Int,
        settingsAction: String,
    ): Boolean {
        if (checkPermission()) return true
        AlertDialog.Builder(requireContext())
            .setMessage(alertRes)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                openSettings(Intent(settingsAction))
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
        return false
    }

    protected fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            GatekeeperToast.show(requireContext(), R.string.power_diagnostics_settings_unavailable)
        }
    }

    companion object {
        const val ARG_PROFILE_SERVICE = "profile_service"
        const val ARG_MAIN_SERVICE = "main_service"
        private const val FETCH_TIMEOUT_SEC = 5L
    }

    /**
     * Список приложений профиля через сервис. Возвращает null, когда вызов не
     * доехал по транспорту; вызывать с фонового потока -- латч блокирует.
     */
    protected fun fetchApps(service: IGatekeeperService): List<io.gatekeeper.util.ApplicationInfoWrapper>? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<io.gatekeeper.util.ApplicationInfoWrapper>? = null
        try {
            service.getApps(object : io.gatekeeper.services.IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<io.gatekeeper.util.ApplicationInfoWrapper>) {
                    result = apps
                    latch.countDown()
                }
            }, true)
        } catch (_: android.os.RemoteException) {
            return null
        }
        latch.await(FETCH_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /** Тост/диалог на UI-потоке, только пока фрагмент жив. */
    protected fun postOnUi(block: () -> Unit) {
        activity?.runOnUiThread {
            if (isAdded) block()
        }
    }

    /** Тумблер с обработчиком; отсутствующий preference молча пропускаем. */
    protected fun bindCheckBox(key: String, checked: Boolean, apply: (Boolean) -> Boolean) {
        val pref = findPreference<SwitchPreferenceCompat>(key) ?: return
        pref.isChecked = checked
        pref.setOnPreferenceChangeListener { _, newState -> apply(newState as Boolean) }
    }
}
