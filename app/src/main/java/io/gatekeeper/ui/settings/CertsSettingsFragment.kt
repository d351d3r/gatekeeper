package io.gatekeeper.ui.settings

import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import io.gatekeeper.R
import io.gatekeeper.util.CaCertificates
import io.gatekeeper.util.GatekeeperToast

/**
 * Экран «Сертификаты» (docs/feature_ca_certs.md). Все операции уходят биндером
 * сервису рабочего профиля: только там приложение -- владелец профиля, и только
 * туда попадает сертификат. Личный профиль не затрагивается.
 */
class CertsSettingsFragment : SettingsSubFragment() {

    private val selectCertFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onCertFileSelected(uri)
        }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_certs)
        findPreference<Preference>(SETTINGS_CA_INSTALL)
            ?.setOnPreferenceClickListener { launchCertPicker() }
        findPreference<Preference>(SETTINGS_CA_INSTALLED)
            ?.setOnPreferenceClickListener { showInstalledCaCerts() }
    }

    private fun launchCertPicker(): Boolean {
        try {
            selectCertFile.launch(arrayOf("*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
        }
        return true
    }

    private fun onCertFileSelected(uri: Uri?) {
        val bytes = uri?.let { readCertBytes(it) }
        val cert = bytes?.let(CaCertificates::parse)
        when {
            bytes == null -> GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
            cert == null -> GatekeeperToast.show(requireContext(), R.string.ca_not_a_certificate)
            else -> showInstallDialog(bytes, cert)
        }
    }

    private fun readCertBytes(uri: Uri): ByteArray? = try {
        requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun showInstallDialog(bytes: ByteArray, cert: java.security.cert.X509Certificate) {
        // Отпечаток обязан быть показан ДО установки -- пользователь сверяет его
        // с опубликованным издателем (docs/feature_ca_certs.md, требования).
        val info = CaCertificates.infoOf(cert)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ca_install_title)
            .setMessage(
                certDetailsText(info) + "\n\n" +
                    getString(R.string.ca_install_limitation) + "\n\n" +
                    getString(R.string.ca_install_monitored_warning)
            )
            .setPositiveButton(R.string.ca_install_action) { _, _ -> installCaCert(bytes) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun certDetailsText(info: CaCertificates.Info): String =
        getString(R.string.ca_cert_details, info.subject, info.sha256)

    private fun installCaCert(cert: ByteArray) {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return
        }
        Thread {
            // null -- успех; "" -- вызов не дошёл по транспорту; иначе текст ошибки DPM.
            val error = try {
                work.installCaCertificate(cert)
            } catch (_: RemoteException) {
                ""
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (error) {
                    null -> GatekeeperToast.show(requireContext(), R.string.ca_install_success)
                    "" -> GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
                    else -> GatekeeperToast.show(
                        requireContext(), getString(R.string.ca_install_failed, error)
                    )
                }
            }
        }.start()
    }

    private fun showInstalledCaCerts(): Boolean {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return true
        }
        Thread {
            val entries = try {
                work.getInstalledCaCertificates()
            } catch (_: RemoteException) {
                null
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (entries == null) {
                    GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
                    return@runOnUiThread
                }
                if (entries.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_ca_installed)
                        .setMessage(R.string.ca_installed_none)
                        .show()
                    return@runOnUiThread
                }
                val infos = entries.mapNotNull(CaCertificates::decodeInfo)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_ca_installed)
                    .setItems(infos.map { certDetailsText(it) }.toTypedArray()) { _, which ->
                        confirmRemoveCaCert(infos[which])
                    }
                    .show()
            }
        }.start()
        return true
    }

    private fun confirmRemoveCaCert(info: CaCertificates.Info) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ca_remove_title)
            .setMessage(certDetailsText(info))
            .setPositiveButton(R.string.ca_remove_action) { _, _ -> removeCaCert(info.sha256) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeCaCert(sha256: String) {
        val work = serviceWork
        if (work == null) {
            GatekeeperToast.show(requireContext(), R.string.ca_no_work_service)
            return
        }
        Thread {
            val removed = try {
                work.removeCaCertificate(sha256)
            } catch (_: RemoteException) {
                false
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                GatekeeperToast.show(
                    requireContext(),
                    if (removed) R.string.ca_remove_success else R.string.ca_remove_failed
                )
            }
        }.start()
    }

    companion object {
        private const val SETTINGS_CA_INSTALL = "settings_ca_install"
        private const val SETTINGS_CA_INSTALLED = "settings_ca_installed"
    }
}
