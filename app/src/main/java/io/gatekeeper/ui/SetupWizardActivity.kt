package io.gatekeeper.ui

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.gatekeeper.R
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility

class SetupWizardActivity : AppCompatActivity() {
    private var policyManager: DevicePolicyManager? = null
    private var storage: LocalStorageManager? = null

    private val provisionProfile: ActivityResultLauncher<Void?> =
        registerForActivityResult(ProfileProvisionContract(), this::setupProfileCb)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (ACTION_PROFILE_PROVISIONED == intent.action && Utility.isWorkProfileAvailable(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_setup_wizard)
        policyManager = getSystemService(DevicePolicyManager::class.java)
        storage = LocalStorageManager.getInstance()
        supportFragmentManager
            .beginTransaction()
            .replace(
                R.id.setup_wizard_container,
                if (ACTION_RESUME_SETUP == intent.action) {
                    ActionRequiredFragment()
                } else {
                    WelcomeFragment()
                }
            )
            .commit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ACTION_PROFILE_PROVISIONED == intent.action && Utility.isWorkProfileAvailable(this)) {
            finishWithResult(true)
        }
    }

    // Экран «Требуется действие» (resume) живёт, пока системный мастер профиля
    // не доведёт провижининг до конца. На прошивках, где финальный шаг прячется
    // (MIUI: кнопка финиша на долю секунды, #1034; «не приходит уведомление»,
    // #1047/#2008), пользователь исправляет это в системном UI и возвращается
    // сюда -- без перезапуска приложения экран сам завершится, как только
    // профиль начнёт маршрутизировать TRY_START_SERVICE обратно.
    override fun onResume() {
        super.onResume()
        if (ACTION_RESUME_SETUP == intent.action && Utility.isWorkProfileAvailable(this)) {
            finishWithResult(true)
        }
    }

    fun <T : BaseWizardFragment> switchToFragment(fragment: T, reverseAnimation: Boolean) {
        supportFragmentManager
            .beginTransaction()
            .setCustomAnimations(
                if (reverseAnimation) R.anim.slide_in_from_left else R.anim.slide_in_from_right,
                if (reverseAnimation) R.anim.slide_out_to_right else R.anim.slide_out_to_left
            )
            .replace(R.id.setup_wizard_container, fragment)
            .commit()
    }

    fun finishWithResult(succeeded: Boolean) {
        setResult(if (succeeded) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    fun setupProfile() {
        if (!policyManager!!.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            switchToFragment(FailedFragment(), false)
            return
        }

        AuthenticationUtility.reset()

        try {
            provisionProfile.launch(null)
        } catch (_: ActivityNotFoundException) {
            switchToFragment(FailedFragment(), false)
        }
    }

    private fun setupProfileCb(result: Boolean) {
        if (result) {
            if (Utility.isWorkProfileAvailable(this)) {
                finishWithResult(true)
                return
            }

            storage!!.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, true)
            switchToFragment(ActionRequiredFragment(), false)
        } else {
            switchToFragment(FailedFragment(), false)
        }
    }

    class SetupWizardContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(context, SetupWizardActivity::class.java)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    class ResumeSetupContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(context, SetupWizardActivity::class.java).apply {
                action = ACTION_RESUME_SETUP
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    private class ProfileProvisionContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            val admin = ComponentName(
                context.applicationContext, GatekeeperDeviceAdminReceiver::class.java
            )
            return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    /**
     * Общий каркас экрана мастера: счетчик шагов с полосой прогресса, заголовок,
     * текст, необязательный список фактов и две кнопки. Раньше все это давала
     * вендоренная com.android.setupwizardlib; теперь это Material 3 и модуль
     * :setup-wizard-lib из сборки убран.
     */
    abstract class BaseWizardFragment : Fragment() {
        protected var setupActivity: SetupWizardActivity? = null

        protected abstract val titleRes: Int
        protected abstract val textRes: Int

        /** 0 -- экран вне последовательности шагов: ожидание, отказ. */
        protected open val step: Int = 0
        protected open val nextLabelRes: Int = R.string.wizard_next
        protected open val backLabelRes: Int = R.string.wizard_back
        protected open val showNext: Boolean = true
        protected open val showBack: Boolean = true

        /** Ожидание: полоса без делений вместо счетчика шагов. */
        protected open val waiting: Boolean = false

        open fun onNavigateNext() {}

        open fun onNavigateBack() {}

        /** Экран проверки устройства дополняет текст списком фактов. */
        protected open fun fillChecks(container: LinearLayout) {}

        override fun onAttach(context: Context) {
            super.onAttach(context)
            setupActivity = context as SetupWizardActivity
        }

        override fun onDetach() {
            super.onDetach()
            setupActivity = null
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.fragment_setup_wizard, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<TextView>(R.id.wizard_title).setText(titleRes)
            view.findViewById<TextView>(R.id.wizard_text).setText(textRes)

            bindProgress(view)
            bindButtons(view)

            val checks = view.findViewById<LinearLayout>(R.id.wizard_checks)
            fillChecks(checks)
            if (checks.isNotEmpty()) {
                checks.visibility = View.VISIBLE
            }

            ViewCompat.setOnApplyWindowInsetsListener(view) { root, windowInsets ->
                val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                root.setPadding(0, bars.top, 0, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        private fun bindProgress(view: View) {
            val progress = view.findViewById<LinearProgressIndicator>(R.id.wizard_progress)
            val label = view.findViewById<TextView>(R.id.wizard_step_label)
            when {
                waiting -> {
                    progress.isIndeterminate = true
                    label.visibility = View.GONE
                }
                step > 0 -> {
                    progress.max = TOTAL_STEPS
                    progress.progress = step
                    label.text = getString(R.string.wizard_step, step, TOTAL_STEPS)
                }
                else -> view.findViewById<View>(R.id.wizard_step_row).visibility = View.GONE
            }
        }

        private fun bindButtons(view: View) {
            val next = view.findViewById<MaterialButton>(R.id.wizard_next)
            next.setText(nextLabelRes)
            next.visibility = if (showNext) View.VISIBLE else View.GONE
            next.setOnClickListener { onNavigateNext() }

            val back = view.findViewById<MaterialButton>(R.id.wizard_back)
            back.setText(backLabelRes)
            back.visibility = if (showBack) View.VISIBLE else View.GONE
            back.setOnClickListener { onNavigateBack() }
        }

        /** Строка факта: иконка плюс текст, без собственной разметки. */
        protected fun addCheck(container: LinearLayout, iconRes: Int, text: String) {
            val context = container.context
            val density = resources.displayMetrics.density
            val row = TextView(context).apply {
                this.text = text
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(ContextCompat.getColor(context, R.color.setupWizardContentText))
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = (CHECK_ICON_GAP_DP * density).toInt()
                val pad = (CHECK_ROW_PADDING_DP * density).toInt()
                setPadding(0, pad, 0, pad)
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        private companion object {
            const val CHECK_ICON_GAP_DP = 12
            const val CHECK_ROW_PADDING_DP = 10
        }
    }

    class WelcomeFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_welcome_title
        override val textRes: Int = R.string.wizard_welcome_text
        override val step: Int = 1
        override val showBack: Boolean = false

        override fun onNavigateNext() {
            setupActivity!!.switchToFragment(PermissionsFragment(), false)
        }
    }

    class PermissionsFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_permissions_title
        override val textRes: Int = R.string.wizard_permissions_text
        override val step: Int = 2

        override fun onNavigateBack() {
            setupActivity!!.switchToFragment(WelcomeFragment(), true)
        }

        override fun onNavigateNext() {
            setupActivity!!.switchToFragment(CheckFragment(), false)
        }
    }

    /**
     * Проверка устройства вместо эссе о прошивках: версия Android, свободен ли
     * слот профиля, известные проблемы вендора. Факты считаются здесь и сейчас,
     * поэтому пользователь видит свое устройство, а не общие слова.
     */
    class CheckFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_check_title
        override val textRes: Int = R.string.wizard_check_text
        override val step: Int = TOTAL_STEPS
        override val nextLabelRes: Int = R.string.wizard_create_profile

        override fun fillChecks(container: LinearLayout) {
            addCheck(
                container,
                R.drawable.ic_check,
                getString(R.string.wizard_check_android, Build.VERSION.RELEASE),
            )

            val policyManager = container.context.getSystemService(DevicePolicyManager::class.java)
            val slotFree = policyManager
                ?.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                ?: false
            addCheck(
                container,
                if (slotFree) R.drawable.ic_check else R.drawable.ic_warning,
                getString(
                    if (slotFree) R.string.wizard_check_slot_free else R.string.wizard_check_slot_busy
                ),
            )

            if (Utility.isMIUI()) {
                addCheck(
                    container,
                    R.drawable.ic_warning,
                    getString(R.string.wizard_check_miui, Build.MANUFACTURER),
                )
            }
        }

        override fun onNavigateBack() {
            setupActivity!!.switchToFragment(PermissionsFragment(), true)
        }

        override fun onNavigateNext() {
            setupActivity!!.switchToFragment(PleaseWaitFragment(), false)
            setupActivity!!.setupProfile()
        }
    }

    class PleaseWaitFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_wait_title
        override val textRes: Int = R.string.wizard_wait_text
        override val waiting: Boolean = true
        override val showNext: Boolean = false
        override val showBack: Boolean = false
    }

    /**
     * Экран, на котором застревают чаще всего (4PDA #1047, #1034, #2008).
     * На Android 7 завершение вешается уведомлением, на 8+ его показывает сама
     * система, поэтому текст говорит про оба случая и про то, что экран
     * закроется сам, как только профиль начнет отвечать (см. onResume).
     */
    class ActionRequiredFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_action_title
        override val textRes: Int = R.string.wizard_action_text
        override val waiting: Boolean = true
        override val showNext: Boolean = false
        override val showBack: Boolean = false
    }

    class FailedFragment : BaseWizardFragment() {
        override val titleRes: Int = R.string.wizard_failed_title
        override val textRes: Int = R.string.wizard_failed_text
        override val nextLabelRes: Int = R.string.wizard_retry
        override val backLabelRes: Int = R.string.wizard_exit

        override fun onNavigateNext() {
            setupActivity!!.switchToFragment(PleaseWaitFragment(), false)
            setupActivity!!.setupProfile()
        }

        override fun onNavigateBack() {
            setupActivity!!.finishWithResult(false)
        }
    }

    companion object {
        /** Экранов с счетчиком: приветствие, права, проверка устройства. */
        private const val TOTAL_STEPS = 3

        const val ACTION_RESUME_SETUP = "io.gatekeeper.RESUME_SETUP"
        const val ACTION_PROFILE_PROVISIONED = "io.gatekeeper.PROFILE_PROVISIONED"
    }
}
