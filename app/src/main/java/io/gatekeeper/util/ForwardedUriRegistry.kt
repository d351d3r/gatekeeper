package io.gatekeeper.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ForwardedUriRegistry<T> {
    private val entries = ConcurrentHashMap<String, T>()

    fun register(value: T, suffix: String): String {
        val path = "/forward/${UUID.randomUUID()}.$suffix"
        entries[path] = value
        return path
    }

    fun get(path: String): T? = entries[path]

    fun remove(path: String) {
        entries.remove(path)
    }
}
