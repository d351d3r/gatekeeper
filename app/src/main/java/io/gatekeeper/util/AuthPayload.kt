package io.gatekeeper.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthPayload {
    val SIGNED_EXTRA_KEYS = listOf(
        "packageName", "package", "apk", "direct_install_apk", "split_apks", "list",
        "linkedPackages", "linkedPackagesShouldFreeze", "shouldFreeze", "name", "boolean",
        "int", "auto_freeze_list", "toast_res_id"
    )

    fun canonicalize(action: String?, timestamp: Long, extras: List<Pair<String, String>>): String {
        val canonicalExtras = extras.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("|") { "${escape(it.first)}=${escape(it.second)}" }
        return "action=${escape(action ?: "")}|timestamp=$timestamp|extras=$canonicalExtras"
    }

    fun encodeStringArray(values: Array<out String>): String = buildString {
        append("sa:")
        values.forEach { value ->
            append(value.length)
            append(':')
            append(value)
        }
    }

    fun sign(keyHex: String?, payload: String): String? = try {
        val key = decodeHex(keyHex) ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    fun verify(keyHex: String?, payload: String, signature: String?): Boolean {
        val expected = sign(keyHex, payload) ?: return false
        val supplied = decodeHex(signature) ?: return false
        return MessageDigest.isEqual(decodeHex(expected), supplied)
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            if (char == '\\' || char == '|' || char == '=') append('\\')
            append(char)
        }
    }

    private fun decodeHex(value: String?): ByteArray? {
        if (value.isNullOrEmpty() || value.length % 2 != 0) return null
        return ByteArray(value.length / 2) { index ->
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) + low).toByte()
        }
    }
}
