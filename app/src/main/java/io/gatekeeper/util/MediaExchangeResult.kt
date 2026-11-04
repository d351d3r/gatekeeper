package io.gatekeeper.util

import java.io.Closeable

/** Ownership of a resource racing an IPC timeout. A late result must be closed exactly once. */
class MediaExchangeResult {
    private var abandoned = false
    private var resource: Closeable? = null

    @Synchronized
    fun arrive(result: Any?) {
        if (result !is Closeable) return
        if (abandoned) result.close() else resource = result
    }

    @Synchronized
    fun claim() { resource = null }

    @Synchronized
    fun abandon() {
        abandoned = true
        val close = resource
        resource = null
        close?.close()
    }
}
