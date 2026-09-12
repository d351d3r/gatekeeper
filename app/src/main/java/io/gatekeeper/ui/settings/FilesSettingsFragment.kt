package io.gatekeeper.ui.settings

import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import androidx.preference.CheckBoxPreference
import io.gatekeeper.R
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility

/** Экран «Файлы и медиа»: кросс-профильный выбор файлов и зеркало медиа. */
class FilesSettingsFragment : SettingsSubFragment() {

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_files)
        bindFileChooser()
        bindMediaMirror()
        applyPlatformRestrictions()
    }

    private fun bindFileChooser() {
        val pref = findPreference<CheckBoxPreference>(SETTINGS_CROSS_PROFILE_FILE_CHOOSER)
            ?: return
        pref.isChecked = manager.getCrossProfileFileChooserEnabled()
        pref.setOnPreferenceChangeListener { _, newState ->
            onFileChooserChange(newState as Boolean)
        }
    }

    private fun onFileChooserChange(enabled: Boolean): Boolean {
        val granted = !enabled || allFilesAccessGranted()
        if (!granted) return false
        manager.setCrossProfileFileChooserEnabled(enabled)
        return true
    }

    private fun allFilesAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return ensureSpecialAccessPermission(
            checkPermission = {
                try {
                    serviceWork?.hasAllFileAccessPermission() == true &&
                        Utility.checkAllFileAccessPermission()
                } catch (_: RemoteException) {
                    false
                }
            },
            alertRes = R.string.request_storage_manager,
            settingsAction = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
        )
    }

    private fun bindMediaMirror() {
        val pref = findPreference<CheckBoxPreference>(SETTINGS_MEDIA_MIRROR) ?: return
        pref.isChecked = LocalStorageManager.getInstance()
            .getBoolean(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED)
        pref.setOnPreferenceChangeListener { _, newState ->
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED, newState as Boolean)
            true
        }
    }

    private fun applyPlatformRestrictions() {
        val pref = findPreference<CheckBoxPreference>(SETTINGS_CROSS_PROFILE_FILE_CHOOSER)
            ?: return
        val am = requireContext().getSystemService(android.app.ActivityManager::class.java)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q || am?.isLowRamDevice == true) {
            pref.isEnabled = false
        }
    }

    companion object {
        private const val SETTINGS_CROSS_PROFILE_FILE_CHOOSER = "settings_cross_profile_file_chooser"
        private const val SETTINGS_MEDIA_MIRROR = "settings_media_mirror"
    }
}
