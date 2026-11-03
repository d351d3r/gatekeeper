package io.gatekeeper

import io.gatekeeper.util.AuthPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPayloadTest {
    private val key = "00112233445566778899aabbccddeeff"

    @Test
    fun canonicalizationIsIndependentOfExtraOrder() {
        val first = AuthPayload.canonicalize(
            "io.gatekeeper.action.INSTALL_PACKAGE", 42,
            listOf("package" to "s:com.example", "apk" to "s:/data/app.apk")
        )
        val second = AuthPayload.canonicalize(
            "io.gatekeeper.action.INSTALL_PACKAGE", 42,
            listOf("apk" to "s:/data/app.apk", "package" to "s:com.example")
        )
        assertEquals(first, second)
    }

    @Test
    fun signatureRejectsActionOrParameterTampering() {
        val payload = AuthPayload.canonicalize("io.gatekeeper.action.INSTALL_PACKAGE", 42,
            listOf("package" to "s:com.example"))
        val signature = AuthPayload.sign(key, payload)

        assertTrue(AuthPayload.verify(key, payload, signature))
        assertFalse(AuthPayload.verify(key, payload.replace("INSTALL", "UNINSTALL"), signature))
        assertFalse(AuthPayload.verify(key, payload.replace("com.example", "com.other"), signature))
    }

    @Test
    fun escapingKeepsDistinctValuesDistinct() {
        val first = AuthPayload.canonicalize("action", 1, listOf("name" to "s:a|b"))
        val second = AuthPayload.canonicalize("action", 1, listOf("name" to "s:a"))
        assertNotEquals(first, second)
    }

    @Test
    fun stringArrayEncodingKeepsBoundariesDistinct() {
        assertNotEquals(
            AuthPayload.encodeStringArray(arrayOf("a\\u001fb")),
            AuthPayload.encodeStringArray(arrayOf("a", "b"))
        )
    }

    @Test
    fun invalidKeysAndSignaturesAreRejected() {
        assertEquals(null, AuthPayload.sign("bad", "payload"))
        assertFalse(AuthPayload.verify(key, "payload", null))
        assertFalse(AuthPayload.verify("bad", "payload", "signature"))
    }

    /**
     * Регрессия batch unfreeze: интент UNFREEZE_ALL_IN_LIST подписывался ДО того,
     * как в него клали «list», и рабочий профиль отвергал подпись молча. Любой
     * подписанный extra, добавленный после подписи, обязан ломать verify.
     */
    @Test
    fun signedPayloadRejectsExtrasAppendedAfterSigning() {
        val signedWithoutList = AuthPayload.canonicalize(
            "io.gatekeeper.action.UNFREEZE_ALL_IN_LIST", 42, emptyList()
        )
        val signature = AuthPayload.sign(key, signedWithoutList)

        assertTrue(AuthPayload.verify(key, signedWithoutList, signature))
        assertFalse(
            AuthPayload.verify(
                key,
                AuthPayload.canonicalize(
                    "io.gatekeeper.action.UNFREEZE_ALL_IN_LIST", 42,
                    listOf("list" to AuthPayload.encodeStringArray(arrayOf("com.android.chrome")))
                ),
                signature
            )
        )
    }

    /** S1: доказательство владения ключом не выдать за подпись служебного интента. */
    @Test
    fun keyProofIsDomainSeparatedFromIntentSignatures() {
        val challenge = "00ff"
        val proofPayload = AuthPayload.proofPayload(challenge)
        val intentPayload = AuthPayload.canonicalize("proof", 0, emptyList())
        assertNotEquals(intentPayload, proofPayload)
        assertTrue(proofPayload.startsWith("proof|"))
        val proof = AuthPayload.sign(key, proofPayload)
        assertTrue(AuthPayload.verify(key, proofPayload, proof))
        assertFalse(AuthPayload.verify(key, AuthPayload.proofPayload("00fe"), proof))
        assertFalse(AuthPayload.verify("ffeeddccbbaa99887766554433221100", proofPayload, proof))
    }
}
