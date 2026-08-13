package io.gatekeeper

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.services.FileShuttleService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Передний план шаттла -- единственное, что не дает процессу другого профиля попасть в кэш
 * и замерзнуть; замерено, что обычного запущенного сервиса для этого мало. Механизм держится
 * на трех вещах сразу: типе в манифесте, разрешении и самом вызове `startForeground`. Отказ
 * любой из них приложение проглатывает одной строкой в лог, поэтому без этих проверок откат
 * всей фазы прошел бы незамеченным -- остальные тесты остаются зелеными.
 */
@RunWith(AndroidJUnit4::class)
class FileShuttleForegroundTest {
    private val context get() = TestProfiles.targetContext

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    @After
    fun stopShuttle() {
        context.stopService(Intent(context, FileShuttleService::class.java))
    }

    @Test
    fun manifestDeclaresDataSyncType() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, FileShuttleService::class.java), 0
        )
        assertEquals(
            "без типа в манифесте startForeground бросает MissingForegroundServiceTypeException",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType
        )
    }

    @Test
    fun foregroundServicePermissionIsGranted() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(
            "без FOREGROUND_SERVICE_DATA_SYNC startForeground бросает SecurityException",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        )
    }

    /**
     * Прод-путь целиком: шаттл поднимается тем же вызовом, что и из реле. Проверять здесь
     * `startService` напрямую бессмысленно -- именно он и есть та строка, без которой
     * `onStartCommand` не вызывается, а привязка с `BIND_AUTO_CREATE` сервис на передний
     * план не выводит.
     */
    @Test
    fun boundShuttleRunsInForeground() {
        val application = context.applicationContext as ShelterApplication
        application.bindFileShuttleService(connection)
        try {
            val name = FileShuttleService::class.java.name
            val manager = context.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var foreground = false
            while (!foreground && SystemClock.elapsedRealtime() < deadline) {
                // С API 26 сюда попадают только собственные сервисы, чужие не видны.
                foreground = manager.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == name && it.foreground }
                if (!foreground) SystemClock.sleep(200)
            }
            assertTrue("поднятый шаттл обязан быть на переднем плане", foreground)
        } finally {
            application.unbindFileShuttleService()
        }
    }
}
