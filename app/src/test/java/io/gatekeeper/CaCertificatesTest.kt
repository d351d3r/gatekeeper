package io.gatekeeper

import io.gatekeeper.util.CaCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class CaCertificatesTest {
    /** Self-signed CN=Gatekeeper Test Root, сгенерирован keytool, DER внутри PEM. */
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIDFzCCAf+gAwIBAgIIC9c5ARSVVjYwDQYJKoZIhvcNAQELBQAwOjEZMBcGA1UE
        ChMQR2F0ZWtlZXBlciBUZXN0czEdMBsGA1UEAxMUR2F0ZWtlZXBlciBUZXN0IFJv
        b3QwHhcNMjYwOTA3MTk1MjQ2WhcNMjYxMDA3MTk1MjQ2WjA6MRkwFwYDVQQKExBH
        YXRla2VlcGVyIFRlc3RzMR0wGwYDVQQDExRHYXRla2VlcGVyIFRlc3QgUm9vdDCC
        ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMy5nuXVwse/bjetsaXanV1p
        f9MExbZVXW+eJFJZ8vhUJEPHaAwVWv/7AjINSIrx43OmHiZaR7qJNnIjA9LpdYeU
        r5D1ZDAXZzOrD6tsnRW7nEHh8k+kZGxIct9CZxIFTn3sjP3hidUL9hCl2+FxrymX
        GjKAUPiOy5swYXToNLM+wji2kmK0w3MivbnGPs1qKvV4QmQR9XvROC9M4YiPkN8x
        WVUdlxznRNdcHybPrbDAdrNLcfAAIV0FPXMgN5zfCJ7IETDBPXJV5SOaC4diVCc5
        tLSNssdtQc4Oa1gTjWlq03iKOwCV1SUDiMR5y8rbtZNKitUrFrO+7pMAZIrwQaEC
        AwEAAaMhMB8wHQYDVR0OBBYEFDraPHwxYYZdu7JOZHmEkHyreZRWMA0GCSqGSIb3
        DQEBCwUAA4IBAQBd8gKx2Uv6ZXfzueegcK1jFXiIte5+gesuIsEjU18+9av+C6WC
        JLrhY5bDTy6pAu79ZmCSaDOAHBLL+4nvcIWn/V4paPX4RJpod7RZWixSHY3z+NAk
        JUn+wQMI/Oz90ZsHwMcJ/rAwGGM0RQY9CH26J71bw4N0EjEMXAUrOoSqUiT4Bjf2
        w9byH0XaGS3z95g/eKkpmuyDdIlU9UMXmBxCSAi979qvz8MY59Ala8Ufzy+v14M7
        hsqAwNmLrDd27NRcMFXU40pnh8WpFBHdwNcJi7DIZKt5cg/pzv8N15Dh3awgaR5B
        XK4lQwVOZdU5DNNWAdv/2cSL8bQ7kGGscLf9
        -----END CERTIFICATE-----
    """.trimIndent().toByteArray()

    @Test
    fun parsesPemCertificate() {
        val cert = CaCertificates.parse(pem)
        assertNotNull(cert)
        assertTrue(
            "subject must expose the CN",
            cert!!.subjectX500Principal.name.contains("Gatekeeper Test Root")
        )
    }

    @Test
    fun rejectsNonCertificateInput() {
        assertNull(CaCertificates.parse("not a certificate".toByteArray()))
        assertNull(CaCertificates.parse(ByteArray(0)))
        assertNull(CaCertificates.parse(ByteArray(2048) { (it % 251).toByte() }))
    }

    @Test
    fun fingerprintIsColonSeparatedUppercaseSha256() {
        val cert = CaCertificates.parse(pem)!!
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val expected = digest.joinToString(":") { "%02X".format(it) }
        assertEquals(expected, CaCertificates.sha256Hex(cert.encoded))
        assertEquals(95, expected.length) // 32 байта -> 64 hex-символа + 31 двоеточие
        assertTrue(expected.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")))
    }

    @Test
    fun fingerprintDistinguishesDifferentBytes() {
        val cert = CaCertificates.parse(pem)!!
        val mutated = cert.encoded.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(
            CaCertificates.sha256Hex(cert.encoded) != CaCertificates.sha256Hex(mutated)
        )
    }

    @Test
    fun infoTransportRoundTrips() {
        val info = CaCertificates.infoOf(CaCertificates.parse(pem)!!)
        val restored = CaCertificates.decodeInfo(CaCertificates.encodeInfo(info))!!
        assertEquals(info, restored)
        assertNull(CaCertificates.decodeInfo("no-separator-here"))
        assertNull(CaCertificates.decodeInfo("|empty-sha"))
        assertNull(CaCertificates.decodeInfo("sha-only|"))
    }

    @Test
    fun subjectWithPipeSurvivesTransport() {
        val info = CaCertificates.Info("CN=a|b", "AA:BB")
        val restored = CaCertificates.decodeInfo(CaCertificates.encodeInfo(info))!!
        assertEquals("AA:BB", restored.sha256)
        assertEquals("CN=a|b", restored.subject)
    }
}
