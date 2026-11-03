package io.gatekeeper

import io.gatekeeper.util.BinderCallers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: биндеры Gatekeeper обслуживают только Gatekeeper -- в своем и соседнем профиле.
 * uid = userId * 100000 + appId, appId у пакета один во всех пользователях.
 */
class BinderCallersTest {
    private val ownPersonal = 10_123 // u0a123
    private val ownWork = 1_010_123 // u10a123

    @Test
    fun sameAppInAnotherProfileIsAccepted() {
        assertEquals(BinderCallers.appIdOf(ownPersonal), BinderCallers.appIdOf(ownWork))
        assertTrue(BinderCallers.isSameApp(ownWork, ownPersonal))
        assertTrue(BinderCallers.isSameApp(ownPersonal, ownWork))
    }

    @Test
    fun anotherAppIsRejectedInAnyProfile() {
        assertFalse(BinderCallers.isSameApp(10_124, ownPersonal))
        assertFalse(BinderCallers.isSameApp(1_010_124, ownPersonal))
    }

    /** Система сама шлет ping и запрос дескриптора интерфейса: их не трогаем. */
    @Test
    fun onlyAidlMethodCodesAreChecked() {
        assertTrue(BinderCallers.isAidlCall(1))
        assertTrue(BinderCallers.isAidlCall(0x00ffffff))
        assertFalse(BinderCallers.isAidlCall(0))
        assertFalse(BinderCallers.isAidlCall(0x5f4e5446)) // INTERFACE_TRANSACTION
        assertFalse(BinderCallers.isAidlCall(0x5f504e47)) // PING_TRANSACTION
    }
}

/** Hex вынесен из AuthenticationUtility: формат ключей и подписей не должен измениться. */
class HexTest {
    @Test
    fun roundTripsUppercaseAndAcceptsLowercase() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        val hex = io.gatekeeper.util.Hex.encode(bytes)
        assertEquals("00017F80FF", hex)
        assertTrue(bytes.contentEquals(io.gatekeeper.util.Hex.decode(hex)))
        assertTrue(bytes.contentEquals(io.gatekeeper.util.Hex.decode(hex.lowercase())))
    }

    @Test
    fun rejectsMalformedInput() {
        assertEquals(null, io.gatekeeper.util.Hex.decode(""))
        assertEquals(null, io.gatekeeper.util.Hex.decode("abc"))
        assertEquals(null, io.gatekeeper.util.Hex.decode("zz"))
    }
}
