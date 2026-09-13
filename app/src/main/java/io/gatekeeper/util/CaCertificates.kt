package io.gatekeeper.util

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Корневые CA рабочего профиля (docs/feature_ca_certs.md). Парсинг выбранного файла и
 * отпечаток SHA-256, который пользователь обязан увидеть и сверить ДО установки.
 * Без android-импортов: логика проверяется JVM-тестами.
 */
object CaCertificates {
    data class Info(val subject: String, val sha256: String)

    /** DER или PEM X.509; всё остальное (в том числе ключи без сертификата) -- null. */
    fun parse(bytes: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
    } catch (_: Exception) {
        null
    }

    /**
     * Отпечаток закодированного сертификата в канонической форме AA:BB:CC:… -- одна и
     * та же строка для показа пользователю и для сверки при удалении (без учёта регистра).
     */
    fun sha256Hex(encoded: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString(":") { "%02X".format(it) }

    fun infoOf(cert: X509Certificate): Info = Info(
        subject = cert.subjectX500Principal?.name ?: "",
        sha256 = sha256Hex(cert.encoded)
    )

    /** Строка вида "SHA256|subject" для транспорта списка через AIDL. */
    fun encodeInfo(info: Info): String = info.sha256 + "|" + info.subject

    fun decodeInfo(encoded: String): Info? {
        val sep = encoded.indexOf('|')
        if (sep <= 0 || sep == encoded.length - 1) {
            return null
        }
        return Info(
            subject = encoded.substring(sep + 1),
            sha256 = encoded.substring(0, sep)
        )
    }
}
