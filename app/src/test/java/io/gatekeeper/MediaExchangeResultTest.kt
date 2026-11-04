package io.gatekeeper

import io.gatekeeper.util.MediaExchangeResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class MediaExchangeResultTest {
    private class Resource : Closeable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun completionBeforeTimeoutClosesUnclaimedResource() {
        val result = MediaExchangeResult()
        val resource = Resource()
        result.arrive(resource)
        result.abandon()
        result.abandon()
        assertEquals(1, resource.closes.get())
    }

    @Test fun timeoutBeforeCompletionClosesLateResource() {
        val result = MediaExchangeResult()
        val resource = Resource()
        result.abandon()
        result.arrive(resource)
        assertEquals(1, resource.closes.get())
    }

    @Test fun claimedResourceBelongsToTheConsumer() {
        val result = MediaExchangeResult()
        val resource = Resource()
        result.arrive(resource)
        result.claim()
        result.abandon()
        assertEquals(0, resource.closes.get())
        resource.close()
        assertEquals(1, resource.closes.get())
    }

    @Test fun concurrentCompletionAndTimeoutNeverLeakOrDoubleClose() {
        repeat(100) {
            val result = MediaExchangeResult()
            val resource = Resource()
            val go = CountDownLatch(1)
            val producer = Thread { go.await(); result.arrive(resource) }
            val timeout = Thread { go.await(); result.abandon() }
            producer.start()
            timeout.start()
            go.countDown()
            producer.join()
            timeout.join()
            assertEquals(1, resource.closes.get())
        }
    }
}
