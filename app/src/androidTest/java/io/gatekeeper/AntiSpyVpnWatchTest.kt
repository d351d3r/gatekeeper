package io.gatekeeper

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.services.AntiSpyVpnWatchService
import io.gatekeeper.util.LocalStorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сторож VPN: тип переднего плана, тишина служебного уведомления и главный переключатель.
 *
 * Тип `systemExempted` разрешен только приложениям из списка исключений платформы, и в личном
 * профиле приложение в него не входит: `startForeground` там кончался `SecurityException`,
 * сервис звал `stopSelf`, будильник поднимал его снова -- цикл перезапуска. Проверка
 * «сервис дошел до переднего плана» ловит это напрямую, проверки типа и разрешения --
 * молчаливый откат манифеста.
 */
@RunWith(AndroidJUnit4::class)
class AntiSpyVpnWatchTest {
    private val context get() = TestProfiles.targetContext
    private val storage get() = LocalStorageManager.getInstance()

    @Before
    fun setUp() {
        LocalStorageManager.initialize(context)
        grantNotifications()
        setWatchSettings(enabled = false, freezeOnVpn = false)
        stopWatcher()
    }

    @After
    fun tearDown() {
        setWatchSettings(enabled = false, freezeOnVpn = false)
        stopWatcher()
    }

    @Test
    fun manifestTypeWorksOutsideTheWorkProfile() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AntiSpyVpnWatchService::class.java), 0
        )
        assertEquals(
            "systemExempted доступен только владельцу профиля, в личном профиле это SecurityException",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType
        )
    }

    @Test
    fun foregroundServicePermissionIsGranted() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(
            "тип specialUse без своего разрешения дает тот же SecurityException",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        )
    }

    @Test
    fun watcherReachesForegroundWhenEnabled() {
        setWatchSettings(enabled = true, freezeOnVpn = true)
        AntiSpyVpnWatchService.syncState(context)
        assertTrue(
            "сторож не дошел до переднего плана: startForeground отказал и сервис ушел в перезапуск",
            awaitWatcher(foreground = true, timeoutMs = 20_000L)
        )
    }

    /**
     * Прямая жалоба пользователя: служебное уведомление фонового сторожа звучит, вибрирует и
     * всплывает поверх экрана (`docs/antispy_settings_requirements.md`).
     */
    @Test
    fun foregroundNotificationIsSilent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        setWatchSettings(enabled = true, freezeOnVpn = true)
        AntiSpyVpnWatchService.syncState(context)
        assumeTrue(awaitWatcher(foreground = true, timeoutMs = 20_000L))

        val nm = context.getSystemService(NotificationManager::class.java)
        val posted = nm.activeNotifications.firstOrNull { it.id == WATCH_NOTIFICATION_ID }
        assertNotNull("сторож на переднем плане обязан иметь уведомление", posted)
        val channel = nm.getNotificationChannel(posted!!.notification.channelId)
        assertNotNull("уведомление ссылается на несуществующий канал", channel)
        // Приложение просит IMPORTANCE_MIN, но уведомление переднего плана скрывать нельзя, и
        // платформа поднимает канал до IMPORTANCE_LOW (в dumpsys: mImportance=2, mOriginalImp=1).
        // LOW -- это уже без звука, вибрации и всплытия; DEFAULT и выше -- нет.
        assertTrue(
            "служебное уведомление сторожа звучит и всплывает: importance=${channel.importance}",
            channel.importance <= NotificationManager.IMPORTANCE_LOW
        )
        assertFalse("канал сторожа не должен вибрировать", channel.shouldVibrate())
    }

    @Test
    fun watcherStaysDownWhileTheMasterSwitchIsOff() {
        setWatchSettings(enabled = false, freezeOnVpn = true)
        AntiSpyVpnWatchService.syncState(context)
        assertFalse(
            "при выключенном главном переключателе сторож не поднимается вовсе",
            awaitWatcher(foreground = null, timeoutMs = 8_000L)
        )
    }

    @Test
    fun watcherStaysDownWithoutASingleTrigger() {
        setWatchSettings(enabled = true, freezeOnVpn = false)
        AntiSpyVpnWatchService.syncState(context)
        assertFalse(
            "сторожить нечего: оба триггера выключены",
            awaitWatcher(foreground = null, timeoutMs = 8_000L)
        )
    }

    @Test
    fun disablingTheSwitchStopsARunningWatcher() {
        setWatchSettings(enabled = true, freezeOnVpn = true)
        AntiSpyVpnWatchService.syncState(context)
        assumeTrue(awaitWatcher(foreground = true, timeoutMs = 20_000L))

        setWatchSettings(enabled = false, freezeOnVpn = true)
        AntiSpyVpnWatchService.syncState(context)
        assertTrue(
            "выключение переключателя обязано снимать уже поднятый сторож",
            awaitWatcherGone(timeoutMs = 10_000L)
        )
    }

    /** @param foreground null -- достаточно самого факта, что сервис жив. */
    private fun awaitWatcher(foreground: Boolean?, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isWatcherRunning(foreground)) return true
            SystemClock.sleep(250)
        }
        return isWatcherRunning(foreground)
    }

    private fun awaitWatcherGone(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isWatcherRunning(null)) return true
            SystemClock.sleep(250)
        }
        return !isWatcherRunning(null)
    }

    private fun isWatcherRunning(foreground: Boolean?): Boolean {
        val name = AntiSpyVpnWatchService::class.java.name
        // С API 26 сюда попадают только собственные сервисы, чужие не видны.
        return context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == name && (foreground == null || it.foreground == foreground) }
    }

    // Синхронно, как в прод-пути: настройку читает процесс :vpnwatch, и с asyncной записью
    // он успевает увидеть старое значение.
    private fun setWatchSettings(enabled: Boolean, freezeOnVpn: Boolean) {
        storage.setBooleanNow(LocalStorageManager.PREF_ANTI_SPY_VPN_WATCH_ENABLED, enabled)
        storage.setBooleanNow(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_VPN, freezeOnVpn)
        storage.setBooleanNow(LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK, false)
    }

    private fun stopWatcher() {
        context.stopService(Intent(context, AntiSpyVpnWatchService::class.java))
        awaitWatcherGone(timeoutMs = 5_000L)
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** `AntiSpyVpnWatchService.NOTIFICATION_ID`. */
        const val WATCH_NOTIFICATION_ID = 0xe49d0
    }
}
