package io.gatekeeper.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.MediaExchangeCoordinator
import io.gatekeeper.util.MediaExchangePolicy
import io.gatekeeper.util.MediaExchangeRuntime
import io.gatekeeper.util.MediaExchangeSettings
import io.gatekeeper.util.MediaExchangeStatus

/** Media settings and their status polling share the fragment's lifecycle. */
class MediaExchangePreferences(
    private val fragment: PreferenceFragmentCompat,
    private val workService: () -> IGatekeeperService?,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    fun bind() {
        val config = MediaExchangeSettings.current()
        toggle("settings_media_mirror", MediaExchangePolicy.ENABLED, config.enabled)
        toggle(MediaExchangePolicy.SCREENSHOTS, MediaExchangePolicy.SCREENSHOTS, config.screenshots)
        toggle(MediaExchangePolicy.CAMERA, MediaExchangePolicy.CAMERA, config.camera)
        fragment.findPreference<Preference>("media_exchange_setup")?.setOnPreferenceClickListener {
            start(askPermission = true)
            true
        }
        fragment.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) { handler.post(update) }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(update) }

    private fun toggle(preference: String, key: String, checked: Boolean) {
        val pref = fragment.findPreference<SwitchPreferenceCompat>(preference) ?: return
        pref.isChecked = checked
        pref.setOnPreferenceChangeListener { _, value ->
            MediaExchangeSettings.set(key, value as Boolean)
            start(askPermission = value && key == MediaExchangePolicy.ENABLED)
            true
        }
    }

    private fun start(askPermission: Boolean) {
        workService()?.let { MediaExchangeCoordinator.remember(it) }
        if (askPermission) MediaExchangeCoordinator.offer(fragment.requireActivity(), explicit = true)
        else MediaExchangeCoordinator.resume(fragment.requireContext())
    }

    private fun refresh() {
        val context = fragment.context ?: return
        val runtime = MediaExchangeRuntime.get(context)
        val config = MediaExchangeSettings.current()
        val incoming = if (!config.enabled || !config.screenshots) "media_exchange_off" else runtime.status
        val outgoing = when {
            !config.enabled || !config.camera -> "media_exchange_off"
            !MediaExchangeCoordinator.hasPermissions(context) -> "media_exchange_permission"
            else -> runtime.remoteStatus
        }
        fragment.findPreference<Preference>("media_exchange_status")?.summary =
            context.getString(R.string.media_exchange_screenshots) + ": " +
            MediaExchangeStatus.text(context, incoming) + "\n" +
            context.getString(R.string.media_exchange_copied, runtime.deliveredCount) + "\n\n" +
            context.getString(R.string.media_exchange_camera) + ": " + MediaExchangeStatus.text(context, outgoing) +
            "\n" + context.getString(R.string.media_exchange_copied, runtime.remoteDeliveredCount)
    }

    companion object { private const val STATUS_INTERVAL_MS = 1500L }
}
