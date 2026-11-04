@file:Suppress("TooGenericExceptionCaught") // Platform boundaries must record/retry failures, including OEM exceptions.

package io.gatekeeper.services

import android.Manifest
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.gatekeeper.R
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.util.MediaExchangeRuntime
import io.gatekeeper.util.MediaExchangeRemote
import io.gatekeeper.util.MediaExchangeStatus
import io.gatekeeper.util.Notifications
import io.gatekeeper.util.StatusNotification
import io.gatekeeper.util.Utility

/** Persistent media observation is a special-use FGS, not an unlimited dataSync session. */
class MediaExchangeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdate = object : Runnable {
        override fun run() {
            val runtime = MediaExchangeRuntime.get(this@MediaExchangeService)
            val notification = Notifications.buildNotification(this@MediaExchangeService, StatusNotification(
                getString(R.string.app_name), getString(R.string.media_exchange_title),
                MediaExchangeStatus.text(this@MediaExchangeService, runtime.status), R.drawable.ic_notification,
            ))
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, Notifications.buildNotification(this, StatusNotification(
            getString(R.string.app_name), getString(R.string.media_exchange_title),
            getString(R.string.media_exchange_notification), R.drawable.ic_notification,
        )))
        try { grantWorkReadPermission() } catch (e: SecurityException) {
            Log.w("MediaExchange", "Work media permission was not granted", e)
        }
        MediaExchangeRuntime.get(this).start()
        handler.post(statusUpdate)
    }
    override fun onBind(intent: Intent?): IBinder = MediaExchangeRuntime.get(this).endpoint.asBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = IMediaExchange.Stub.asInterface(intent?.getBundleExtra("media")?.getBinder("peer"))
        if (peer != null) {
            val runtime = MediaExchangeRuntime.get(this)
            Thread {
                try {
                    if (runtime.attach(peer)) MediaExchangeRemote(peer).connect(runtime.endpoint)
                } catch (e: Exception) { Log.w("MediaExchange", "Peer will need reconnection", e) }
            }.start()
        }
        // Retain the live opposite endpoint across a system restart of one process.
        return START_REDELIVER_INTENT
    }
    override fun onDestroy() {
        handler.removeCallbacks(statusUpdate)
        MediaExchangeRuntime.get(this).stop()
        super.onDestroy()
    }

    private fun grantWorkReadPermission() {
        if (!Utility.isProfileOwner(this)) return
        val manager = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, GatekeeperDeviceAdminReceiver::class.java)
        val permissions = buildList {
            add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        for (permission in permissions) {
            manager.setPermissionGrantState(
                admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4d4544
        private const val STATUS_INTERVAL_MS = 5000L
        fun start(context: Context, peer: IMediaExchange? = null): Boolean = try {
            val intent = Intent(context, MediaExchangeService::class.java)
            if (peer != null) intent.putExtra("media", Bundle().apply { putBinder("peer", peer.asBinder()) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            true
        } catch (e: RuntimeException) {
            Log.w("MediaExchange", "Foreground media observation could not start", e)
            false
        }
    }
}
