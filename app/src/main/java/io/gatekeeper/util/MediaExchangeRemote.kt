package io.gatekeeper.util

import android.os.ParcelFileDescriptor
import io.gatekeeper.services.IMediaExchange
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.io.IOException

/** Bound IPC waits: a frozen OEM process must not consume an unlimited number of threads or FDs. */
class MediaExchangeRemote(private val target: IMediaExchange) : IMediaExchange by target {
    override fun isWorkProfile(): Boolean = call { target.isWorkProfile }
    override fun configuration(): String = call { target.configuration() }
    override fun identity(): String = call { target.identity() }
    override fun latestId(): Long = call { target.latestId() }
    override fun images(afterId: Long, limit: Int, configuration: String): MutableList<String> =
        call { target.images(afterId, limit, configuration) }
    override fun describe(id: Long, configuration: String): String? = call { target.describe(id, configuration) }
    override fun open(id: Long, version: String, configuration: String): ParcelFileDescriptor =
        call { target.open(id, version, configuration) }
    override fun connect(peer: IMediaExchange) = call { target.connect(peer) }
    override fun status(): String = call { target.status() }

    companion object {
        private const val IPC_TIMEOUT_SECONDS = 10L
        private val calls = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "media-exchange-ipc").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

        @Suppress("ThrowsCount") // Preserve permission denial, interruption and a bounded IPC timeout.
        fun <T> call(block: () -> T): T {
            val ownership = MediaExchangeResult()
            val future = calls.submit<T> {
                val result = block()
                ownership.arrive(result)
                result
            }
            return try { future.get(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS).also { ownership.claim() } }
            catch (error: TimeoutException) {
                abandon(ownership)
                throw IllegalStateException("Media IPC timed out", error)
            } catch (error: InterruptedException) {
                abandon(ownership)
                Thread.currentThread().interrupt()
                throw IllegalStateException("Media IPC interrupted", error)
            } catch (error: ExecutionException) {
                throw (error.cause as? SecurityException) ?: IllegalStateException("Media IPC unavailable", error)
            }
        }

        private fun abandon(ownership: MediaExchangeResult) {
            try { ownership.abandon() }
            catch (error: IOException) { android.util.Log.w("MediaExchange", "Late FD close failed", error) }
        }
    }
}
