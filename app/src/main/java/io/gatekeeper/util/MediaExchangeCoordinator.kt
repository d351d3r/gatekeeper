@file:Suppress("TooGenericExceptionCaught") // Platform boundaries must record/retry failures, including OEM exceptions.

package io.gatekeeper.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.services.MediaExchangeService
import io.gatekeeper.ui.MediaExchangeSetupActivity
import java.util.concurrent.Executors

/** Called only after the existing core-service key proof, from a visible personal UI. */
object MediaExchangeCoordinator {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var workService: IGatekeeperService? = null
    @Volatile private var connecting = false

    fun remember(service: IGatekeeperService) { workService = service }

    fun permissions(): Array<String> = buildList {
        add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    fun hasPermissions(context: Context): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) ||
            permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @Suppress("ReturnCount") // Permission/feature gates exit before launching UI.
    fun offer(activity: Activity, explicit: Boolean = false) {
        if (!MediaExchangeSettings.current().enabled) return
        if (hasPermissions(activity)) {
            resume(activity)
            return
        }
        val storage = LocalStorageManager.getInstance()
        val key = "media_exchange_permission_offered"
        if (!explicit && storage.getBoolean(key)) return
        storage.setBooleanNow(key, true)
        activity.startActivity(Intent(activity, MediaExchangeSetupActivity::class.java))
    }

    @Suppress("ReturnCount") // Disabled or unconfigured workflows cannot start a service.
    fun resume(context: Context) {
        val app = context.applicationContext
        val runtime = MediaExchangeRuntime.get(app)
        runtime.configurationChanged()
        if (!MediaExchangeSettings.current().enabled) {
            app.stopService(Intent(app, MediaExchangeService::class.java))
            return
        }
        val remote = workService ?: return
        if (!hasPermissions(app) || connecting) return
        if (!MediaExchangeService.start(app)) return
        connecting = true
        worker.execute {
            try {
                val peer = MediaExchangeRemote.call { remote.connectMediaExchange(runtime.endpoint) }
                runtime.attach(peer)
                MediaExchangeService.start(app, peer)
            } catch (e: Exception) {
                Log.w("MediaExchange", "Open Gatekeeper to reconnect the media bridge", e)
            } finally { connecting = false }
        }
    }
}
