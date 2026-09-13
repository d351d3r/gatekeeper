package io.gatekeeper

import io.gatekeeper.util.LegacyFreezeMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFreezeMigrationTest {
    @Test
    fun completesOnlyAfterEveryRequiredItemSucceeds() {
        val handled = mutableListOf<String>()

        val completed = LegacyFreezeMigration.run(
            items = listOf("keep", "first", "second"),
            requiresAction = { it != "keep" },
            action = { handled += it }
        )

        assertTrue(completed)
        assertEquals(listOf("first", "second"), handled)
    }

    @Test
    fun doesNotCompleteWhenAnUnfreezeFails() {
        val handled = mutableListOf<String>()

        val completed = LegacyFreezeMigration.run(
            items = listOf("first", "broken", "later"),
            requiresAction = { true },
            action = {
                handled += it
                if (it == "broken") throw IllegalStateException("binder lost")
            }
        )

        assertFalse(completed)
        assertEquals(listOf("first", "broken"), handled)
    }
}
