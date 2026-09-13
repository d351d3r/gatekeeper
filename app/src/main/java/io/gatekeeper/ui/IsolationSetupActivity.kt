package io.gatekeeper.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.gatekeeper.R
import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

/**
 * Выбор изоляции: отдельный экран, а не диалог.
 *
 * Это единственное решение, которое приложение просит принять, и принимается оно
 * один раз за жизнь профиля. Наверху -- два уровня переключателем, как в опросе.
 * Строка «Дополнительная настройка» разворачивает те же политики по отдельности
 * прямо здесь: раньше она уводила на экран настроек, и человек, нажавший «по
 * отдельности», получал не тумблеры, а еще один список.
 *
 * И уровень, и отдельный тумблер применяются сразу (SettingsManager сам шлет
 * SYNC_ISOLATION в профиль), поэтому «Готово» только помечает выбор сделанным.
 */
class IsolationSetupActivity : AppCompatActivity() {

    private val manager get() = SettingsManager.getInstance()
    private lateinit var choice: RadioGroup
    private val switches = mutableMapOf<String, MaterialSwitch>()

    /** Гасит слушатели, пока состояние проставляется программно, чтобы не зациклить. */
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isolation_setup)

        val root = findViewById<View>(R.id.isolation_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        choice = findViewById(R.id.isolation_choice)
        buildSwitches()
        refreshFromState()

        choice.setOnCheckedChangeListener { _, checkedId ->
            if (suppress) return@setOnCheckedChangeListener
            manager.applyIsolationPreset(checkedId == R.id.isolation_choice_isolated)
            refreshSwitchesFromState()
        }

        bindAdvancedToggle()

        findViewById<MaterialButton>(R.id.isolation_done).setOnClickListener {
            // Набор уже применен слушателями. Если выбран уровень (а не ручной набор),
            // применяем его еще раз: свежий профиль мог не иметь состояния вовсе.
            when (choice.checkedRadioButtonId) {
                R.id.isolation_choice_convenient -> manager.applyIsolationPreset(false)
                R.id.isolation_choice_isolated -> manager.applyIsolationPreset(true)
            }
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_ISOLATION_PRESET_CHOSEN, true)
            finish()
        }
    }

    private fun bindAdvancedToggle() {
        val advanced = findViewById<TextView>(R.id.isolation_advanced)
        val container = findViewById<View>(R.id.isolation_switches_container)
        advanced.setOnClickListener {
            val show = container.visibility != View.VISIBLE
            container.visibility = if (show) View.VISIBLE else View.GONE
            advanced.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (show) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                0,
            )
        }
    }

    private fun buildSwitches() {
        val container = findViewById<LinearLayout>(R.id.isolation_switches_container)
        val pkg = packageName
        for (policy in IsolationPolicies.ALL) {
            val titleId = resources.getIdentifier("isolation_${policy.key}", "string", pkg)
            if (titleId == 0) continue
            val row = MaterialSwitch(this).apply {
                setText(titleId)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@IsolationSetupActivity,
                        R.color.setupWizardContentText,
                    )
                )
                val pad = (ROW_VERTICAL_PADDING_DP * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
                setOnCheckedChangeListener { _, checked ->
                    if (suppress) return@setOnCheckedChangeListener
                    manager.setIsolationPolicy(policy.key, checked)
                    refreshPresetFromState()
                }
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            switches[policy.key] = row
        }
    }

    private fun refreshFromState() {
        refreshPresetFromState()
        refreshSwitchesFromState()
    }

    private fun refreshPresetFromState() = withSuppressed {
        when (IsolationPolicies.matchedPreset(manager.getIsolationState())) {
            false -> choice.check(R.id.isolation_choice_convenient)
            true -> choice.check(R.id.isolation_choice_isolated)
            else -> choice.clearCheck()
        }
    }

    private fun refreshSwitchesFromState() = withSuppressed {
        for ((key, view) in switches) {
            view.isChecked = manager.getIsolationPolicy(key)
        }
    }

    private inline fun withSuppressed(block: () -> Unit) {
        suppress = true
        try {
            block()
        } finally {
            suppress = false
        }
    }

    private companion object {
        const val ROW_VERTICAL_PADDING_DP = 10
    }
}
