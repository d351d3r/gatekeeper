package io.gatekeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.util.LocalEvents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Шина событий процесса вместо LocalBroadcastManager: та же семантика, на которую
 * опираются экраны (асинхронно, на главном потоке, снятый получатель молчит).
 */
@RunWith(AndroidJUnit4::class)
class LocalEventsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val receivers = ArrayList<BroadcastReceiver>()

    private inner class Recorder(expected: Int = 1) : BroadcastReceiver() {
        val got = CopyOnWriteArrayList<Intent>()
        var onMainThread = true
        var receivedContext: Context? = null
        val latch = CountDownLatch(expected)

        override fun onReceive(context: Context, intent: Intent) {
            onMainThread = onMainThread && Looper.myLooper() == Looper.getMainLooper()
            receivedContext = context
            got += intent
            latch.countDown()
        }
    }

    private fun register(action: String, expected: Int = 1): Recorder =
        Recorder(expected).also {
            receivers += it
            LocalEvents.register(it, IntentFilter(action))
        }

    @After
    fun tearDown() {
        receivers.forEach(LocalEvents::unregister)
    }

    @Test
    fun deliversToMatchingReceiversOnTheMainThread() {
        val recorder = register(ACTION)
        assertTrue(LocalEvents.send(context, Intent(ACTION).putExtra("n", 7)))
        assertTrue(recorder.latch.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertEquals(7, recorder.got.single().getIntExtra("n", 0))
        assertTrue(recorder.onMainThread)
        assertSame(context.applicationContext, recorder.receivedContext)
    }

    @Test
    fun nonMatchingActionReachesNobody() {
        val recorder = register(ACTION)
        assertFalse(LocalEvents.send(context, Intent("$ACTION.OTHER")))
        instrumentation.waitForIdleSync()
        assertTrue(recorder.got.isEmpty())
    }

    /** Экран, закрытый между отправкой и доставкой, событие уже не получает. */
    @Test
    fun receiverRemovedBeforeDeliveryGetsNothing() {
        val recorder = register(ACTION)
        instrumentation.runOnMainSync {
            LocalEvents.send(context, Intent(ACTION))
            LocalEvents.unregister(recorder)
        }
        instrumentation.waitForIdleSync()
        assertTrue(recorder.got.isEmpty())
    }

    @Test
    fun everyRegistrationOfTheActionIsServed() {
        val first = register(ACTION)
        val second = register(ACTION)
        LocalEvents.send(context, Intent(ACTION))
        assertTrue(first.latch.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(second.latch.await(TIMEOUT_S, TimeUnit.SECONDS))
    }

    private companion object {
        const val ACTION = "io.gatekeeper.test.LOCAL_EVENT"
        const val TIMEOUT_S = 5L
    }
}
