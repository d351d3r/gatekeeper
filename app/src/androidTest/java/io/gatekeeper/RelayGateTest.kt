package io.gatekeeper

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.ProfileActions
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Гейт DummyActivity на устройстве: решение покрыто JVM-тестом RelayAuthorizationTest,
 * здесь -- что activity его действительно спрашивает. Команда -- безвредная запись
 * пробной настройки через SYNCHRONIZE_PREFERENCE.
 */
@RunWith(AndroidJUnit4::class)
class RelayGateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var storage: LocalStorageManager
    private var previousKey: String? = null
    private var previousBootstrapped = false
    private var previousPeerConfirmed = false

    @Before
    fun setUp() {
        storage = LocalStorageManager.getInstance()
        previousKey = storage.getString(LocalStorageManager.PREF_AUTH_KEY)
        previousBootstrapped = storage.getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
        previousPeerConfirmed = storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
        storage.removeNow(PROBE)
    }

    @After
    fun tearDown() {
        storage.removeNow(PROBE)
        storage.removeNow(LocalStorageManager.PREF_AUTH_KEY)
        previousKey?.let { storage.setStringNow(LocalStorageManager.PREF_AUTH_KEY, it) }
        storage.setBooleanNow(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED, previousBootstrapped)
        storage.setBooleanNow(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED, previousPeerConfirmed)
    }

    @Test
    fun unsignedPrivilegedCommandIsRejected() {
        runDummy(probeIntent())
        assertFalse(storage.getBoolean(PROBE))
    }

    /** Контроль: та же команда с подписью проходит, отказ выше -- из-за подписи. */
    @Test
    fun signedCommandIsApplied() {
        AuthenticationUtility.currentKey()
        val intent = probeIntent()
        AuthenticationUtility.signIntent(intent)
        assertTrue("интент не подписан", intent.hasExtra("signature"))

        runDummy(intent)
        assertTrue(storage.getBoolean(PROBE))
    }

    private fun probeIntent(): Intent = Intent(ProfileActions.SYNCHRONIZE_PREFERENCE)
        .setClassName(context, DummyActivity::class.java.name)
        .putExtra("name", PROBE)
        .putExtra("boolean", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Запустить DummyActivity и дождаться, пока она закроется: решение гейта уже принято. */
    private fun runDummy(intent: Intent): Activity {
        val monitor = instrumentation.addMonitor(DummyActivity::class.java.name, null, false)
        try {
            context.startActivity(intent)
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(TIMEOUT_MS)) {
                "DummyActivity не запустилась"
            }
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (!activity.isFinishing && !activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(POLL_MS)
            }
            assertTrue("DummyActivity не закрылась", activity.isFinishing || activity.isDestroyed)
            return activity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private companion object {
        const val PROBE = "relay_gate_test_probe"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
