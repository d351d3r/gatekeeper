package io.gatekeeper

import io.gatekeeper.util.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `same key yields the same code`() {
        val key = "F24D0BE29E763C8914D2DDCB336BDAC0EB9EA3B72AE849B1076FCDB7E3E9A544"
        assertEquals(PairingCode.of(key), PairingCode.of(key))
    }

    @Test
    fun `different keys yield different codes`() {
        val a = "F24D0BE29E763C8914D2DDCB336BDAC0EB9EA3B72AE849B1076FCDB7E3E9A544"
        val b = "9D7E78CDA039F707B16FD135234E61D8EBEE3A3CFF11DEB5CB53FB1F03DB6321"
        assertNotEquals(PairingCode.of(a), PairingCode.of(b))
    }

    @Test
    fun `code is six digits in two groups`() {
        val code = PairingCode.of("00")
        assertEquals(7, code.length)
        assertEquals(' ', code[3])
        assertTrue(code.replace(" ", "").all { it.isDigit() })
    }

    /** Ведущие нули нельзя терять: код сверяется посимвольно. */
    @Test
    fun `codes keep leading zeroes`() {
        var withLeadingZero: String? = null
        for (i in 0 until 5000) {
            val code = PairingCode.of(i.toString()).replace(" ", "")
            if (code.startsWith("0")) {
                withLeadingZero = code
                break
            }
        }
        assertEquals(6, withLeadingZero?.length)
    }
}
