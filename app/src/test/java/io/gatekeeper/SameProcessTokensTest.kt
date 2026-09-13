package io.gatekeeper

import io.gatekeeper.util.SameProcessTokens
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SameProcessTokensTest {
    @After
    fun tearDown() {
        SameProcessTokens.resetForTest()
    }

    @Test
    fun issuedTokenCanBeConsumedOnlyOnce() {
        val token = SameProcessTokens.issue()

        assertTrue(SameProcessTokens.consume(token))
        assertFalse(SameProcessTokens.consume(token))
    }

    @Test
    fun foreignTokenDoesNotConsumeValidToken() {
        val token = SameProcessTokens.issue()

        assertFalse(SameProcessTokens.consume("not-issued"))
        assertTrue(SameProcessTokens.consume(token))
    }

    @Test
    fun tokensAreUnpredictable() {
        assertNotEquals(SameProcessTokens.issue(), SameProcessTokens.issue())
    }
}
