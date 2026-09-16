package io.gatekeeper.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.gatekeeper.GatekeeperApplication
import io.gatekeeper.services.IFileShuttleService
import io.gatekeeper.services.IFileShuttleServiceCallback
import io.gatekeeper.util.Utility

/**
 * Релей файлового шаттла: START_FILE_SHUTTLE / START_FILE_SHUTTLE_2. На pre-R
 * просит WRITE_EXTERNAL_STORAGE, на R+ — all-files access. После разрешения
 * биндит FileShuttleService и передаёт колбэк, прилетевший с intent, обратно
 * в запросивший процесс. Вынесено из DummyActivity.
 */
class FileShuttleFlow(private val activity: DummyActivity) {
    private var pendingCallback: IFileShuttleServiceCallback? = null

    fun handleStartFileShuttle() {
        val callback = IFileShuttleServiceCallback.Stub.asInterface(
            activity.intent.getBundleExtra(EXTRA)?.getBinder(CALLBACK),
        )
        if (callback == null) {
            Log.w(TAG, "file shuttle refused: callback is missing")
            activity.finish()
            return
        }
        pendingCallback = callback
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                doStartFileShuttle(callback)
            } else {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_EXTERNAL_STORAGE,
                )
            }
        } else {
            // "Поверх других приложений" здесь больше не нужно: шаттл запрашивают только
            // видимые activity, фонового старта в этом пути не осталось.
            if (Utility.checkAllFileAccessPermission()) {
                doStartFileShuttle(callback)
            } else {
                Log.w(TAG, "file shuttle refused: no all-files access in this profile")
                activity.finish()
            }
        }
    }

    /** Ветка REQUEST_EXTERNAL_STORAGE из onRequestPermissionsResult. */
    fun onStoragePermissionResult(granted: Boolean) {
        val callback = pendingCallback
        if (granted && callback != null) {
            doStartFileShuttle(callback)
        } else {
            activity.finish()
        }
    }

    private fun doStartFileShuttle(callback: IFileShuttleServiceCallback) {
        (activity.application as GatekeeperApplication).bindFileShuttleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val shuttle = IFileShuttleService.Stub.asInterface(service)
                    try {
                        callback.callback(shuttle)
                    } catch (_: RemoteException) {
                    }
                    if (pendingCallback?.asBinder() === callback.asBinder()) {
                        pendingCallback = null
                    }
                    activity.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Do Nothing
                }
            },
        )
    }

    companion object {
        private const val TAG = "FileShuttleFlow"
        private const val EXTRA = "extra"
        private const val CALLBACK = "callback"
        internal const val REQUEST_EXTERNAL_STORAGE = 2
    }
}
