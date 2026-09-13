package io.gatekeeper.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import io.gatekeeper.GatekeeperApplication

/**
 * Релей биндера главного сервиса: START_SERVICE. Личная сторона биндит
 * GatekeeperService и возвращает биндер вызывающему через setResult —
 * так кросс-профильные вызовы получают AIDL-канал. Вынесено из DummyActivity.
 */
class ServiceRelayFlow(private val activity: DummyActivity) {

    fun handleStartService() {
        (activity.application as GatekeeperApplication).bindMainService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val data = Intent()
                    val bundle = Bundle().apply {
                        putBinder("service", service)
                    }
                    data.putExtra("extra", bundle)
                    activity.setResult(Activity.RESULT_OK, data)
                    activity.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // dummy
                }
            },
            true,
        )
    }
}
