package io.gatekeeper.ui.settings

import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
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
        findPreference<Preference>(SETTINGS_CA_INSTALL)?.setOnPreferenceClickListener {
            try {
                selectCertFile.launch(arrayOf("*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInstalled()
    }

    private fun onCertFileSelected(uri: Uri?) {
        val bytes = uri?.let {
            try {
                requireContext().contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
            } catch (_: Exception) {
                null
            }
        }
        val cert = bytes?.let(CaCertificates::parse)
        when {
            bytes == null -> GatekeeperToast.show(requireContext(), R.string.ca_read_failed)
            cert == null -> GatekeeperToast.show(requireContext(), R.string.ca_not_a_certificate)
            else -> showInstallDialog(bytes, cert)
        }
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
                refreshInstalled()
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

    /**
     * Установленные сертификаты -- строками экрана, а не списком в диалоге: их
     * отпечатки нужно сверять глазами, а диалог для этого приходится открывать.
     * Состояние живет в рабочем профиле, поэтому спрашиваем его на каждый onResume.
     */
    private fun refreshInstalled() {
        val category = findPreference<PreferenceCategory>(SETTINGS_CA_LIST) ?: return
        val work = serviceWork
        if (work == null) {
            fillList(category, emptyList(), getString(R.string.ca_no_work_service))
            return
        }
        Thread {
            val entries = try {
                work.getInstalledCaCertificates()
            } catch (_: RemoteException) {
                null
            }
            postOnUi {
                when {
                    entries == null ->
                        fillList(category, emptyList(), getString(R.string.ca_no_work_service))
                    entries.isEmpty() ->
                        fillList(category, emptyList(), getString(R.string.ca_installed_none))
                    else ->
                        fillList(category, entries.mapNotNull(CaCertificates::decodeInfo), null)
                }
            }
        }.start()
    }

    private fun fillList(
        category: PreferenceCategory,
        infos: List<CaCertificates.Info>,
        placeholder: String?,
    ) {
        category.removeAll()
        if (placeholder != null) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = placeholder
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            )
            return
        }
        for (info in infos) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = info.subject
                    summary = info.sha256
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        confirmRemoveCaCert(info)
                        true
                    }
                }
            )
        }
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
                refreshInstalled()
            }
        }.start()
    }

    companion object {
        private const val SETTINGS_CA_INSTALL = "settings_ca_install"
        private const val SETTINGS_CA_LIST = "settings_ca_list"
    }
}
