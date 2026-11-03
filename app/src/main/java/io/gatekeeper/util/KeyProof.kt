package io.gatekeeper.util

import java.security.SecureRandom

/**
 * Доказательство владения общим ключом без его передачи (S1, docs/audit_android17.md).
 *
 * Личная сторона получает биндер рабочего сервиса из результата реле, а результат мог
 * вернуть не Gatekeeper, а приложение, выбранное в системном «Открыть с помощью».
 * Поэтому до передачи биндеру прокси и данных личная сторона шлет случайный вызов, и
 * только сторона с общим ключом отвечает верно.
 */
object KeyProof {
    private const val CHALLENGE_BYTES = 16

    /** Случайный вызов: 128 бит в hex. */
    fun newChallenge(): String {
        val bytes = ByteArray(CHALLENGE_BYTES)
        SecureRandom().nextBytes(bytes)
        return Hex.encode(bytes)
    }

    /** Ответ на вызов своим ключом; null -- ключа нет. */
    fun prove(challenge: String): String? {
        val key = storedKey() ?: return null
        return AuthPayload.sign(key, AuthPayload.proofPayload(challenge))
    }

    /** Ответ [proof] на наш вызов [challenge] посчитан нашим ключом. */
    fun verify(challenge: String, proof: String?): Boolean =
        AuthPayload.verify(storedKey(), AuthPayload.proofPayload(challenge), proof)

    private fun storedKey(): String? =
        LocalStorageManager.getInstance()
            .getString(LocalStorageManager.PREF_AUTH_KEY)
            ?.takeIf { Hex.decode(it) != null }
}
