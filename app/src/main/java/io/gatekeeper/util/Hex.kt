package io.gatekeeper.util

/** Hex-кодировка ключей и подписей реле. Без Android SDK. */
object Hex {
    private const val RADIX = 16
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val BYTE_MASK = 0xFF
    private val digits = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and BYTE_MASK
            chars[j * 2] = digits[v ushr NIBBLE_BITS]
            chars[j * 2 + 1] = digits[v and NIBBLE_MASK]
        }
        return String(chars)
    }

    /** null -- строка пустая, нечетной длины или с не-hex символами. */
    fun decode(s: String): ByteArray? {
        val values = s.map { Character.digit(it, RADIX) }
        val valid = s.isNotEmpty() && s.length % 2 == 0 && values.none { it < 0 }
        return if (!valid) {
            null
        } else {
            ByteArray(s.length / 2) { i -> ((values[i * 2] shl NIBBLE_BITS) + values[i * 2 + 1]).toByte() }
        }
    }
}
