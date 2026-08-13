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
}
