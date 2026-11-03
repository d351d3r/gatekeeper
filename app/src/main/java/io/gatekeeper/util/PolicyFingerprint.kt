package io.gatekeeper.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Отпечаток желаемого набора политик рабочего профиля (F4, docs/audit_android17.md).
 *
 * Раньше каждый запуск реле в профиле стирал все кросс-профильные фильтры и собирал их
 * заново: десятки вызовов DevicePolicyManager на главном потоке, а пока фильтров нет,
 * встречный запрос из личного профиля не находил форвардер. Теперь набор пересобирается,
 * только когда изменилось то, из чего он строится, а также после обновления приложения,
 * перезагрузки и раз в [MAX_AGE_MS] как страховка от правок мимо Gatekeeper.
 *
 * Без Android SDK: проверяется JVM-тестами.
 */
object PolicyFingerprint {
    /** Не дольше суток без полной сверки. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Порядок входов значим только как порядок; разделитель экранируется. */
    fun of(inputs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (input in inputs) {
            digest.update(input.replace("\\", "\\\\").replace("\n", "\\n").toByteArray(StandardCharsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Можно не применять заново: отпечаток совпал и сверка была недавно. */
    fun isFresh(stored: String?, current: String, appliedAtMs: Long, nowMs: Long): Boolean =
        stored != null && stored == current && nowMs >= appliedAtMs && nowMs - appliedAtMs < MAX_AGE_MS
}
