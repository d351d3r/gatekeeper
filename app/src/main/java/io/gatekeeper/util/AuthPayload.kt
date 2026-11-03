package io.gatekeeper.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthPayload {
    /**
     * Extras, которые входят в подпись. Каждый extra, который читает обработчик реле,
     * обязан быть здесь или в явном списке неподписываемых (RelayExtrasSignedTest):
     * неподписанный extra подделка, перехватившая подписанный интент, переписывает и
     * отправляет повторно в окне подписи.
     */
    val SIGNED_EXTRA_KEYS = listOf(
        "packageName", "package", "apk", "direct_install_apk", "split_apks", "list",
        "linkedPackages", "linkedPackagesShouldFreeze", "shouldFreeze", "name", "boolean",
        "int", "auto_freeze_list", "toast_res_id",
        // SYNC_ISOLATION: весь набор политик изоляции. Без подписи его можно было
        // переписать в пустой и выключить изоляцию профиля целиком.
        ProfileActions.EXTRA_ISOLATION_KEYS,
        // FREEZE_ALL_IN_LIST: уведомление вместо тоста при фоновой VPN-заморозке.
        "vpn_origin",
    )

    private const val CLOCK_SKEW_MS = 2_000L
    private const val MAX_AGE_MS = 30_000L

    /**
     * Подпись свежая: не старше [MAX_AGE_MS] и не из будущего дальше [CLOCK_SKEW_MS].
     * Часы у профилей одни, но подпись и проверка разнесены во времени; окно же
     * ограничивает повтор перехваченного интента.
     */
    fun isFresh(nowMs: Long, signedAtMs: Long): Boolean =
        (nowMs - signedAtMs) in -CLOCK_SKEW_MS..<MAX_AGE_MS

    fun canonicalize(action: String?, timestamp: Long, extras: List<Pair<String, String>>): String {
        val canonicalExtras = extras.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("|") { "${escape(it.first)}=${escape(it.second)}" }
        return "action=${escape(action ?: "")}|timestamp=$timestamp|extras=$canonicalExtras"
    }

    /**
     * Что подписывает сторона, доказывая владение ключом в ответ на вызов. Префикс не
     * совпадает ни с одним каноническим представлением интента ("action=..."), поэтому
     * доказательство нельзя выдать за подпись служебного действия и наоборот.
     */
    fun proofPayload(challenge: String): String = "proof|" + escape(challenge)

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
