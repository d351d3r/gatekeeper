package io.gatekeeper

import io.gatekeeper.util.PolicyFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F4: набор политик профиля пересобирается, только когда изменились входы. */
class PolicyFingerprintTest {
    private val inputs = listOf("version=21", "share=false", "iso:no_autofill=true")

    @Test
    fun sameInputsGiveSameFingerprint() {
        assertEquals(PolicyFingerprint.of(inputs), PolicyFingerprint.of(inputs.toList()))
    }

    @Test
    fun anyChangedInputChangesTheFingerprint() {
        val changed = inputs.toMutableList().also { it[1] = "share=true" }
        assertNotEquals(PolicyFingerprint.of(inputs), PolicyFingerprint.of(changed))
    }

    /** Разделитель входов экранируется: склейка двух строк не совпадает с одной. */
    @Test
    fun inputBoundariesAreUnambiguous() {
        assertNotEquals(
            PolicyFingerprint.of(listOf("a\nb")),
            PolicyFingerprint.of(listOf("a", "b")),
        )
    }

    @Test
    fun freshOnlyWhenStoredMatchesAndIsRecent() {
        val fp = PolicyFingerprint.of(inputs)
        val now = 1_000_000_000L
        assertTrue(PolicyFingerprint.isFresh(fp, fp, now - 1_000, now))
        assertFalse(PolicyFingerprint.isFresh(null, fp, now - 1_000, now))
        assertFalse(PolicyFingerprint.isFresh("other", fp, now - 1_000, now))
    }

    @Test
    fun staleAfterTheMaxAge() {
        val fp = PolicyFingerprint.of(inputs)
        val now = 10L * PolicyFingerprint.MAX_AGE_MS
        assertTrue(PolicyFingerprint.isFresh(fp, fp, now - PolicyFingerprint.MAX_AGE_MS + 1, now))
        assertFalse(PolicyFingerprint.isFresh(fp, fp, now - PolicyFingerprint.MAX_AGE_MS, now))
    }

    /** Часы ушли назад (смена времени): лучше пересобрать, чем доверять отметке. */
    @Test
    fun clockGoingBackwardsIsNotFresh() {
        val fp = PolicyFingerprint.of(inputs)
        assertFalse(PolicyFingerprint.isFresh(fp, fp, appliedAtMs = 2_000, nowMs = 1_000))
    }
}
