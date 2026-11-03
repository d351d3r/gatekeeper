package io.gatekeeper

import io.gatekeeper.util.AuthPayload
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.Hex
import io.gatekeeper.util.KeyProof
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S1: личная сторона доверяет биндеру рабочего сервиса только после того, как он
 * ответил на случайный вызов общим ключом. Ответ подделки из «Открыть с помощью»
 * не должен проходить ни при каких входных данных.
 */
class KeyProofTest {
    @Before
    fun setUp() {
        TestStorage.install()
    }

    @Test
    fun challengesAreFresh128BitHex() {
        val first = KeyProof.newChallenge()
        assertEquals(32, first.length)
        assertNotNull(Hex.decode(first))
        assertNotEquals(first, KeyProof.newChallenge())
    }

    @Test
    fun proofWithTheSharedKeyVerifies() {
        AuthenticationUtility.currentKey()
        val challenge = KeyProof.newChallenge()
        assertTrue(KeyProof.verify(challenge, KeyProof.prove(challenge)))
    }

    @Test
    fun proofWithAnotherKeyIsRejected() {
        AuthenticationUtility.currentKey()
        val challenge = KeyProof.newChallenge()
        val foreign = AuthPayload.sign("00".repeat(32), AuthPayload.proofPayload(challenge))
        assertFalse(KeyProof.verify(challenge, foreign))
        assertFalse(KeyProof.verify(challenge, null))
        assertFalse(KeyProof.verify(challenge, ""))
    }

    /** Ответ привязан к своему вызову: перехваченный ответ на другой вызов не годится. */
    @Test
    fun proofIsBoundToItsChallenge() {
        AuthenticationUtility.currentKey()
        val proof = KeyProof.prove(KeyProof.newChallenge())
        assertFalse(KeyProof.verify(KeyProof.newChallenge(), proof))
    }

    /** Подпись реле нашим ключом не выдается за доказательство: разные домены полезной нагрузки. */
    @Test
    fun relaySignatureIsNotAProof() {
        val key = AuthenticationUtility.currentKey()
        val challenge = KeyProof.newChallenge()
        assertFalse(KeyProof.verify(challenge, AuthPayload.sign(key, challenge)))
    }

    @Test
    fun withoutAValidKeyNothingProvesOrVerifies() {
        val challenge = KeyProof.newChallenge()
        assertNull(KeyProof.prove(challenge))
        assertFalse(KeyProof.verify(challenge, "00"))

        LocalStorageManager.getInstance().setString(LocalStorageManager.PREF_AUTH_KEY, "not-hex")
        assertNull(KeyProof.prove(challenge))
        assertFalse(KeyProof.verify(challenge, "00"))
    }
}

/** Жизненный цикл общего ключа реле: создание, принятие чужого, сброс. */
class AuthKeyLifecycleTest {
    private lateinit var storage: LocalStorageManager

    @Before
    fun setUp() {
        TestStorage.install()
        storage = LocalStorageManager.getInstance()
    }

    @Test
    fun ownKeyIsCreatedOnceAs256BitHex() {
        val key = AuthenticationUtility.currentKey()
        assertEquals(64, key.length)
        assertNotNull(Hex.decode(key))
        assertEquals(key, AuthenticationUtility.currentKey())
    }

    /** Битый ключ в настройках не используется для подписи, а заменяется новым. */
    @Test
    fun malformedStoredKeyIsReplaced() {
        storage.setString(LocalStorageManager.PREF_AUTH_KEY, "zz")
        val key = AuthenticationUtility.currentKey()
        assertNotEquals("zz", key)
        assertEquals(key, storage.getString(LocalStorageManager.PREF_AUTH_KEY))
    }

    @Test
    fun malformedKeyIsNotAdopted() {
        val own = AuthenticationUtility.currentKey()
        assertFalse(AuthenticationUtility.adoptKey("xyz"))
        assertFalse(AuthenticationUtility.adoptKey(""))
        assertEquals(own, storage.getString(LocalStorageManager.PREF_AUTH_KEY))
        assertFalse(storage.getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED))
    }

    /** Принятый ключ пришел от другой стороны -- он у нее есть, слать его больше незачем. */
    @Test
    fun adoptedKeyIsBootstrappedAndConfirmed() {
        val foreign = "AB".repeat(32)
        assertTrue(AuthenticationUtility.adoptKey(foreign))
        assertEquals(foreign, AuthenticationUtility.currentKey())
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED))
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED))
    }

    @Test
    fun resetForgetsTheKeyAndThePeer() {
        AuthenticationUtility.adoptKey("AB".repeat(32))
        AuthenticationUtility.reset()
        assertNull(storage.getString(LocalStorageManager.PREF_AUTH_KEY))
        assertFalse(storage.getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED))
        assertFalse(storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED))
    }

    @Test
    fun peerConfirmationSticks() {
        AuthenticationUtility.markPeerConfirmed()
        AuthenticationUtility.markPeerConfirmed()
        assertTrue(storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED))
    }
}
