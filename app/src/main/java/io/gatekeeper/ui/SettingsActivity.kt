package io.gatekeeper.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.gatekeeper.R
import io.gatekeeper.ui.settings.SettingsSubFragment

/**
 * Хост настроек. Корень -- [SettingsFragment]; подэкраны приезжают через
 * OnPreferenceStartFragmentCallback (атрибут app:fragment у строк корня) и
 * кладутся в бэкстек. Биндер сервиса рабочего профиля пробрасывается
 * фрагментам через arguments.
 */
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.settings_toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            val root = SettingsFragment().apply {
                arguments = Bundle().apply {
                    putBinder(
                        SettingsSubFragment.ARG_PROFILE_SERVICE,
                        intent.extras?.getBundle("extras")?.getBinder("profile_service"),
                    )
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, root)
                .commit()
        }
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragmentClass = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, fragmentClass)
            .apply {
                arguments = Bundle().apply {
                    putBinder(
                        SettingsSubFragment.ARG_PROFILE_SERVICE,
                        caller.arguments?.getBinder(SettingsSubFragment.ARG_PROFILE_SERVICE),
                    )
                    putAll(pref.extras)
                }
            }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        supportActionBar?.title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            supportActionBar?.title = getString(R.string.settings)
            return true
        }
        finish()
        return true
    }

    companion object {
        const val EXTRA_OPEN_POWER_DIAGNOSTICS = "open_power_diagnostics"
    }
}
