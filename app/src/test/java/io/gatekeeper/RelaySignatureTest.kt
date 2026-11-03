package io.gatekeeper

import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.MainActivity
import io.gatekeeper.ui.PendingPackageOperations
import io.gatekeeper.ui.ProfileRepairFlow
import io.gatekeeper.util.AntiSpyManager
import io.gatekeeper.util.AuthPayload
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.ProfileActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Окно свежести подписи реле: −2 с … 30 с от момента подписи. */
class SignatureWindowTest {
    private val signedAt = 1_700_000_000_000L

    @Test
    fun acceptsSignaturesInsideTheWindow() {
        assertTrue(AuthPayload.isFresh(signedAt, signedAt))
        assertTrue(AuthPayload.isFresh(signedAt + 29_999, signedAt))
        assertTrue(AuthPayload.isFresh(signedAt - 2_000, signedAt))
    }

    /** Перехваченный интент нельзя отправить повторно позже чем через 30 с. */
    @Test
    fun rejectsStaleSignatures() {
        assertFalse(AuthPayload.isFresh(signedAt + 30_000, signedAt))
        assertFalse(AuthPayload.isFresh(signedAt + 86_400_000, signedAt))
    }

    @Test
    fun rejectsSignaturesFromTheFuture() {
        assertFalse(AuthPayload.isFresh(signedAt - 2_001, signedAt))
    }

    /** Интент без отметки времени читается как 0 -- и отвергается. */
    @Test
    fun missingTimestampIsRejected() {
        assertFalse(AuthPayload.isFresh(signedAt, 0L))
    }
}

/** Каноническая запись значений extras: обе стороны реле обязаны кодировать одинаково. */
class SignedValueEncodingTest {
    @Test
    fun scalarsCarryTheirType() {
        assertEquals("s:com.bank", AuthenticationUtility.encodeSignedValue("com.bank"))
        assertEquals("b:true", AuthenticationUtility.encodeSignedValue(true))
        assertEquals("i:5", AuthenticationUtility.encodeSignedValue(5))
        assertEquals("l:5", AuthenticationUtility.encodeSignedValue(5L))
        assertNotEquals(AuthenticationUtility.encodeSignedValue(5), AuthenticationUtility.encodeSignedValue(5L))
    }

    @Test
    fun arraysAreEncodedWithBoundaries() {
        assertEquals(
            AuthPayload.encodeStringArray(arrayOf("a", "b")),
            AuthenticationUtility.encodeSignedValue(arrayOf("a", "b")),
        )
        assertEquals("ba:true,false", AuthenticationUtility.encodeSignedValue(booleanArrayOf(true, false)))
    }

    /** Тип, который не кодируется, в подпись не входит -- таких extras у обработчиков быть не должно. */
    @Test
    fun unsupportedTypesAreNotSigned() {
        assertNull(AuthenticationUtility.encodeSignedValue(null))
        assertNull(AuthenticationUtility.encodeSignedValue(intArrayOf(1)))
        assertNull(AuthenticationUtility.encodeSignedValue(arrayOf<Any>("a", 1)))
        assertNull(AuthenticationUtility.encodeSignedValue(1.5))
    }
}

/**
 * Каждый extra, который читает обработчик реле, подписан или явно неподписываем.
 * Неподписанный extra подделка, перехватившая подписанный интент в «Открыть с помощью»,
 * переписывает и отправляет повторно в окне подписи: так было с isolation_keys у
 * SYNC_ISOLATION -- пустой набор выключал изоляцию профиля целиком.
 */
class RelayExtrasSignedTest {
    private val handlerFiles = listOf(
        "DummyActivity.kt", "WorkRelayHandlers.kt", "DummyBatchFlow.kt", "UnfreezeLaunchFlow.kt",
        "PackageInstallFlow.kt", "FileShuttleFlow.kt", "ServiceRelayFlow.kt", "ProvisionFlow.kt",
        "ProfileRepairFlow.kt", "PowerSettingsFlow.kt", "AntiSpyLaunchFlow.kt",
    )

    /** Константы, которыми обработчики называют extras. Новая константа -- новая строка здесь. */
    private val constants = mapOf(
        "EXTRA_SAME_PROCESS_NONCE" to "same_process_nonce",
        "MainActivity.EXTRA_TOAST_RES_ID" to MainActivity.EXTRA_TOAST_RES_ID,
        "AntiSpyManager.EXTRA_AUTO_FREEZE_LIST" to AntiSpyManager.EXTRA_AUTO_FREEZE_LIST,
        "DummyActivity.EXTRA_VPN_ORIGIN" to DummyActivity.EXTRA_VPN_ORIGIN,
        "ProfileActions.EXTRA_ISOLATION_KEYS" to ProfileActions.EXTRA_ISOLATION_KEYS,
        "EXTRA_AUTH_KEY" to ProfileRepairFlow.EXTRA_AUTH_KEY,
        "EXTRA" to "extra",
        "PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID" to
            PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID,
        "PackageInstaller.EXTRA_STATUS" to "android.content.pm.extra.STATUS",
        "Intent.EXTRA_INTENT" to "android.intent.extra.INTENT",
    )

    /** Подписать нельзя или незачем -- с причиной. */
    private val unsignedByDesign = mapOf(
        "same_process_nonce" to "одноразовый токен своего процесса, профиль не пересекает",
        ProfileRepairFlow.EXTRA_AUTH_KEY to "сам ключ: первое доверие или перепривязка со сверкой кода человеком",
        "callback" to "Bundle с биндером; Gatekeeper только сообщает в него итог, свои биндеры проверяют вызывающего",
        "extra" to "Bundle с биндером File Shuttle, та же причина",
        PendingPackageOperations.PENDING_PACKAGE_OPERATION_ID to
            "колбэк PackageInstaller через неэкспортированный псевдоним",
        "android.content.pm.extra.STATUS" to "колбэк PackageInstaller через неэкспортированный псевдоним",
        "android.intent.extra.INTENT" to "колбэк PackageInstaller через неэкспортированный псевдоним",
    )

    private val patterns = listOf(
        Regex("""(?<!IntentCompat\.)\bget\w*Extra\(\s*([^,()\s]+)"""),
        Regex("""IntentCompat\.get\w*Extra\(\s*[^,]+,\s*([^,()\s]+)"""),
        Regex("""\bhasExtra\(\s*([^,()\s]+)"""),
        Regex("""\.extras!?!?\??\.get\w*\(\s*([^,()\s]+)"""),
    )

    private val readKeys: Map<String, String> by lazy {
        val found = LinkedHashMap<String, String>()
        for (name in handlerFiles) {
            val source = File("src/main/java/io/gatekeeper/ui/$name").readText()
            for (pattern in patterns) {
                for (match in pattern.findAll(source)) {
                    val token = match.groupValues[1]
                    found[token] = "$name: $token"
                }
            }
        }
        found
    }

    private fun keyOf(token: String): String? =
        if (token.startsWith("\"")) token.trim('"') else constants[token]

    @Test
    fun scanSeesTheKnownReads() {
        val keys = readKeys.keys.mapNotNull(::keyOf).toSet()
        assertTrue(keys.toString(), ProfileActions.EXTRA_ISOLATION_KEYS in keys)
        assertTrue(keys.toString(), "packageName" in keys)
        assertTrue(keys.toString(), "callback" in keys)
    }

    @Test
    fun everyExtraNameIsResolved() {
        for ((token, where) in readKeys) {
            assertTrue("неизвестная константа extra -- добавьте в constants: $where", keyOf(token) != null)
        }
    }

    @Test
    fun everyExtraReadByARelayHandlerIsSigned() {
        for ((token, where) in readKeys) {
            val key = keyOf(token) ?: continue
            assertTrue(
                "extra «$key» читается без подписи ($where)",
                key in AuthPayload.SIGNED_EXTRA_KEYS || key in unsignedByDesign,
            )
        }
    }
}
