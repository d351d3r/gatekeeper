package io.gatekeeper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import io.gatekeeper.R
import io.gatekeeper.util.IsolationPolicies
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

/**
 * Выбор пресета изоляции: отдельный экран, а не диалог.
 *
 * Это единственное решение, которое приложение просит принять, и принимается оно
 * один раз за жизнь профиля. В диалоге такой выбор читается как помеха, которую
 * надо закрыть, поэтому здесь полный экран с переключателем и отдельной строкой
 * для тех, кто хочет собрать набор руками.
 *
 * Вызывается из [MainActivity], когда профиль есть, а выбор не сделан: так вопрос
 * доживает и до тех путей, где мастер не отработал.
 */
class IsolationSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isolation_setup)

        val root = findViewById<android.view.View>(R.id.isolation_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val choice = findViewById<RadioGroup>(R.id.isolation_choice)
        // Предвыбран текущий набор, а не «ничего»: экран можно открыть и повторно.
        val isolated = IsolationPolicies.matchedPreset(
            SettingsManager.getInstance().getIsolationState()
        ) == true
        choice.check(
            if (isolated) R.id.isolation_choice_isolated else R.id.isolation_choice_convenient
        )

        findViewById<TextView>(R.id.isolation_advanced).setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    putExtra(SettingsActivity.EXTRA_OPEN_SCREEN, SCREEN_ISOLATION)
                    // Ведем сразу к переключателям: человек нажал «по отдельности»,
                    // а не «покажи мне те же два пресета еще раз».
                    putExtra(SettingsActivity.EXTRA_SCROLL_TO, SWITCHES_CATEGORY)
                }
            )
        }

        findViewById<MaterialButton>(R.id.isolation_done).setOnClickListener {
            SettingsManager.getInstance().applyIsolationPreset(
                choice.checkedRadioButtonId == R.id.isolation_choice_isolated
            )
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_ISOLATION_PRESET_CHOSEN, true)
            finish()
        }
    }

    private companion object {
        const val SCREEN_ISOLATION = "settings_root_isolation"
        const val SWITCHES_CATEGORY = "isolation_switches"
    }
}
