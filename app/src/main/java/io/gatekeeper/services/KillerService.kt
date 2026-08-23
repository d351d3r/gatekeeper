package io.gatekeeper.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

// KillerService is a dirty fix to the fact that Activities cannot receive any event
// about their removal from recent tasks. Since we need to keep the VPN watch alive when
// the app is closed by any means, we ensure Anti Spy monitoring persists.
class KillerService : Service() {
    private var serviceMain: IGatekeeperService? = null
    private var serviceWork: IGatekeeperService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.getBundleExtra("extra")
        serviceMain = IGatekeeperService.Stub.asInterface(extras?.getBinder("main"))
        serviceWork = IGatekeeperService.Stub.asInterface(extras?.getBinder("work"))
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Anti Spy is always on — keep VPN watch alive.
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        keepVpnWatchAlive()
    }

    private fun keepVpnWatchAlive() {
        try {
            serviceWork?.stopGatekeeperService(true)
        } catch (_: Exception) {
        }
        try {
            serviceMain?.stopGatekeeperService(false)
        } catch (_: Exception) {
        }
        AntiSpyVpnWatchService.syncState(applicationContext)
        stopSelf()
    }
}
