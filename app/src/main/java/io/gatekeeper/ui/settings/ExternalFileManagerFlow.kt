package io.gatekeeper.ui.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import androidx.appcompat.app.AlertDialog
import io.gatekeeper.R
import io.gatekeeper.util.FileShuttleConnection
import io.gatekeeper.util.FileShuttleExternalBrowser
import io.gatekeeper.util.GatekeeperToast

internal class ExternalFileManagerFlow(private val fragment: FilesSettingsFragment) {
    fun show() {
        val context = fragment.requireContext()
        val targets = FileShuttleExternalBrowser.findTargets(context)
        if (targets.isEmpty()) {
            GatekeeperToast.show(context, R.string.external_file_manager_unavailable)
            return
        }
        if (targets.size == 1) {
            val target = targets[0]
            confirm(
                ComponentName(target.activityInfo.packageName, target.activityInfo.name),
                target.loadLabel(context.packageManager),
            )
            return
        }
        val labels = targets.map { it.loadLabel(context.packageManager) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.external_file_manager_choose)
            .setItems(labels) { _, which ->
                val target = targets[which]
                confirm(
                    ComponentName(target.activityInfo.packageName, target.activityInfo.name),
                    labels[which],
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(component: ComponentName, label: CharSequence) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.external_file_manager_confirm_title, label))
            .setMessage(R.string.external_file_manager_confirm_message)
            .setPositiveButton(R.string.external_file_manager_open) { _, _ -> open(component) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun open(component: ComponentName) {
        if (FileShuttleConnection.peek() != null) {
            start(component)
            return
        }
        if (!FileShuttleConnection.requestBind(fragment.requireActivity())) {
            unavailable()
            return
        }
        Thread {
            val connected = FileShuttleConnection.awaitBinder(FileShuttleConnection.BIND_TIMEOUT_MS) != null
            fragment.postOnUi {
                if (connected) start(component) else unavailable()
            }
        }.start()
    }

    private fun start(component: ComponentName) {
        try {
            fragment.startActivity(FileShuttleExternalBrowser.browseIntent(component))
        } catch (_: ActivityNotFoundException) {
            GatekeeperToast.show(
                fragment.requireContext(),
                R.string.external_file_manager_unavailable,
            )
        }
    }

    private fun unavailable() {
        GatekeeperToast.show(fragment.requireContext(), R.string.file_shuttle_unavailable)
    }
}
