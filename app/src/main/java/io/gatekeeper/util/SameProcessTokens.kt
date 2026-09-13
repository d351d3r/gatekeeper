package io.gatekeeper.util

import android.os.SystemClock
import java.security.SecureRandom

object SameProcessTokens {
    private const val TOKEN_BYTES = 32
    private const val MAX_AGE_MS = 5_000L
    private const val MAX_PENDING = 128
    private val random = SecureRandom()
    private val tokens = LinkedHashMap<String, Long>()

    @Synchronized
    fun issue(): String {
        removeExpired(SystemClock.elapsedRealtime())
        while (tokens.size >= MAX_PENDING) {
            tokens.remove(tokens.entries.first().key)
        }
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens[token] = SystemClock.elapsedRealtime()
        return token
    }

    @Synchronized
    fun consume(token: String?): Boolean {
        val now = SystemClock.elapsedRealtime()
        removeExpired(now)
        val issuedAt = token?.let { tokens.remove(it) } ?: return false
        return now - issuedAt <= MAX_AGE_MS
    }

    @Synchronized
    fun resetForTest() {
        tokens.clear()
    }

    private fun removeExpired(now: Long) {
        tokens.entries.removeIf { now - it.value > MAX_AGE_MS }
    }
}
