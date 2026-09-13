package io.gatekeeper.ui.settings

import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.services.IAppInstallCallback
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.AutoFreezeDefaults
import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

/**
 * Экран «Резервная копия» (C4, 4PDA #947, #948, #957). Данные приложений без root
 * недоступны -- экспортируем честные границы: настройки из
 * [BackupPayload.EXPORTABLE_SETTINGS] (без ключа авторизации), пакеты обоих
 * профилей и список автозаморозки. Файл -- JSON через SAF, никаких прав на хранилище.
 */
class BackupSettingsFragment : SettingsSubFragment() {

    private val createBackup =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            this::onBackupLocationPicked,
        )

    private val openBackup =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
            this::onBackupFilePicked,
        )

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_backup)
        findPreference<Preference>(SETTINGS_BACKUP_EXPORT)
            ?.setOnPreferenceClickListener { createBackup.launch(BACKUP_FILE_NAME); true }
        findPreference<Preference>(SETTINGS_BACKUP_IMPORT)
            ?.setOnPreferenceClickListener { openBackup.launch(arrayOf("*/*")); true }
    }

    private fun onBackupLocationPicked(uri: Uri?) {
        if (uri == null) return
        val main = serviceMain
        val work = serviceWork
        Thread {
            val local = LocalStorageManager.getInstance()
            val payload = BackupPayload.Payload(
                settings = local.snapshotSettings(BackupPayload.EXPORTABLE_SETTINGS),
                mainApps = main?.let { fetchApps(it) }?.map { it.getPackageName() } ?: emptyList(),
                workApps = work?.let { fetchApps(it) }?.map { it.getPackageName() } ?: emptyList(),
                autoFreezeWork = local.getStringList(
                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE
                ).toList(),
            )
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(BackupPayload.serialize(payload).toByteArray())
                } != null
            }.getOrDefault(false)
            postOnUi {
                GatekeeperToast.show(
                    requireContext(),
                    getString(
                        if (ok) R.string.backup_export_success else R.string.backup_export_failed
                    ),
                )
            }
        }.start()
    }

    private fun onBackupFilePicked(uri: Uri?) {
        if (uri == null) return
        Thread {
            val text = runCatching {
                requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val payload = text?.let(BackupPayload::parse)
            postOnUi { applyImportedPayload(payload) }
        }.start()
    }

    private fun applyImportedPayload(payload: BackupPayload.Payload?) {
        if (payload == null) {
            GatekeeperToast.show(requireContext(), R.string.backup_import_invalid)
            return
        }
        LocalStorageManager.getInstance().applySettings(payload.settings)
        // Компоненты (провайдер файлов, платёжный стаб) и сторож
        // VPN подхватывают значения из префов; правила ссылок C2
        // применяем сразу, не дожидаясь тумблера в настройках.
        manager.applyAll()
        applyImportedLinkRules()
        GatekeeperToast.show(
            requireContext(),
            getString(
                R.string.backup_import_success,
                payload.settings.size,
                payload.mainApps.size,
                payload.workApps.size,
            ),
        )
        maybeOfferAppRestore(payload)
    }

    /**
     * C2: правила ссылок из бэкапа лежат в префах после applySettings, но в
     * профиле ещё не применены -- догоняем сервис рабочего профиля. Отказ
     * не критичен: список в префах, применится при следующем enforce.
     */
    private fun applyImportedLinkRules() {
        val work = serviceWork ?: return
        val rules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
            .toList()
        Thread {
            runCatching { work.setCrossProfileLinkRules(rules) }
        }.start()
    }

    /**
     * C4+: после импорта предлагаем доклонировать недостающие приложения
     * рабочего профиля из списка бэкапа. Донор -- личный профиль; кандидаты
     * считаются чистой функцией [BackupPayload.restorableWorkApps].
     */
    private fun maybeOfferAppRestore(payload: BackupPayload.Payload) {
        val main = serviceMain
        val work = serviceWork
        if (main == null || work == null) return
        Thread {
            val mainApps = fetchApps(main)
            val workApps = fetchApps(work)
            val restorable = if (mainApps == null || workApps == null) {
                emptyList()
            } else {
                val workInstalled = workApps.map { it.getPackageName() }.toSet()
                val mainInstalled = mainApps.map { it.getPackageName() }.toSet()
                BackupPayload.restorableWorkApps(payload.workApps, workInstalled, mainInstalled)
            }
            val byPkg = mainApps?.associateBy { it.getPackageName() } ?: emptyMap()
            val candidates = restorable.mapNotNull { byPkg[it] }
            if (candidates.isEmpty()) return@Thread
            postOnUi { showRestoreOffer(candidates, work) }
        }.start()
    }

    private fun showRestoreOffer(
        candidates: List<ApplicationInfoWrapper>,
        work: IGatekeeperService,
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_title)
            .setMessage(getString(R.string.backup_restore_message, candidates.size))
            .setPositiveButton(R.string.backup_restore_now) { _, _ ->
                restoreWorkAppsSequentially(candidates, work)
            }
            .setNegativeButton(R.string.backup_restore_later, null)
            .show()
    }

    /**
     * Ставим восстанавливаемые приложения строго по одному: параллельные
     * PackageInstaller-сессии кладут резолвер. Каждый успех возвращается
     * в список автозаморозки, как при ручном клонировании.
     */
    private fun restoreWorkAppsSequentially(apps: List<ApplicationInfoWrapper>, work: IGatekeeperService) {
        var index = 0
        var failed = 0
        fun installNext() {
            if (index >= apps.size) {
                postOnUi {
                    GatekeeperToast.show(
                        requireContext(),
                        getString(
                            R.string.backup_restore_done,
                            apps.size - failed,
                            apps.size,
                            failed,
                        ),
                    )
                }
                return
            }
            val app = apps[index]
            index++
            val callback = object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    if (result == android.app.Activity.RESULT_OK) {
                        AutoFreezeDefaults.enableForWorkProfile(
                            requireContext(),
                            app.getPackageName(),
                            clearOptOut = true,
                        )
                    } else {
                        failed++
                    }
                    installNext()
                }
            }
            try {
                work.installApp(app, callback)
            } catch (_: RemoteException) {
                failed++
                installNext()
            }
        }
        installNext()
    }

    companion object {
        private const val SETTINGS_BACKUP_EXPORT = "settings_backup_export"
        private const val SETTINGS_BACKUP_IMPORT = "settings_backup_import"
        private const val BACKUP_FILE_NAME = "gatekeeper-backup.json"
    }
}
