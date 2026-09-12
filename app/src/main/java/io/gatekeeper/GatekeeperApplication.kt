package io.gatekeeper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.util.Log
import com.google.android.material.color.DynamicColors
import io.gatekeeper.services.FileShuttleService
import io.gatekeeper.services.GatekeeperService
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

class GatekeeperApplication : Application() {
    private var mainServiceConnection: ServiceConnection? = null
    private var fileShuttleServiceConnection: ServiceConnection? = null

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(this)
        SettingsManager.initialize(this)
        // Палитра следует системе: динамический цвет накладывается там, где он
        // есть (Android 12+), и обычная схема там, где нет. Переключателя нет
        // (settings-plan.md): решение о фирменной палитре -- за продуктом.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    fun bindMainService(conn: ServiceConnection, foreground: Boolean) {
        unbindMainService()
        val intent = Intent(applicationContext, GatekeeperService::class.java)
        intent.putExtra("foreground", foreground)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        mainServiceConnection = conn
    }

    /**
     * Шаттл запускается, а не только привязывается. Привязка к самому себе оставляет процесс
     * кэшированным, а кэшированный процесс система замораживает через несколько секунд;
     * синхронная транзакция в замороженный процесс стоит ему жизни
     * (`Killing ... Sync transaction while frozen`), то есть связь рвется на первой же паузе.
     * Запущенный сервис держит процесс вне кэша, пока идет сессия работы с файлами.
     */
    fun bindFileShuttleService(conn: ServiceConnection) {
        unbindFileShuttleService()
        val intent = Intent(applicationContext, FileShuttleService::class.java)
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            // Вызов не из видимой activity. Привязка все равно поднимет сервис, но процесс
            // останется кэшированным и замерзнет: связь проживет до первого запроса
            // пользователя, который этот процесс и убьет.
            Log.w(TAG, "file shuttle started in background", e)
        }
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        fileShuttleServiceConnection = conn
    }

    fun unbindMainService() {
        mainServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
                // Service may already be unbound.
            }
        }
        mainServiceConnection = null
    }

    fun unbindFileShuttleService() {
        fileShuttleServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
            }
        }
        fileShuttleServiceConnection = null
    }

    companion object {
        private const val TAG = "GatekeeperApplication"
    }
}
