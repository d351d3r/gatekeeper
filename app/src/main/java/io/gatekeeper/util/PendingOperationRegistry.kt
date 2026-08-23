package io.gatekeeper.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PendingOperationRegistry<T> {
    private val entries = ConcurrentHashMap<String, T>()

    fun register(value: T): String {
        val id = UUID.randomUUID().toString()
        entries[id] = value
        return id
    }

    fun consume(id: String): T? = entries.remove(id)
}
