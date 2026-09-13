package io.gatekeeper.util

import java.security.MessageDigest

/**
 * Шестизначный код сверки для перепривязки профилей.
 *
 * Обе стороны выводят его из одного и того же предлагаемого ключа, поэтому
 * совпадение кода означает, что запрос пришел именно от Gatekeeper в личном
 * профиле. Чужое приложение форварднутый интент подделать может, а показать
 * тот же код на экране личного Gatekeeper -- нет: подтверждение в рабочем
 * профиле держится именно на этой сверке.
 */
object PairingCode {
    private const val RANGE = 1_000_000L
    private const val BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFFL
    private const val LENGTH = 6
    private const val GROUP = 3

    fun of(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.US_ASCII))
        var value = 0L
        for (i in 0 until BYTES) {
            value = (value shl BITS_PER_BYTE) or (digest[i].toLong() and BYTE_MASK)
        }
        val code = (value % RANGE).toString().padStart(LENGTH, '0')
        return code.substring(0, GROUP) + " " + code.substring(GROUP)
    }
}
