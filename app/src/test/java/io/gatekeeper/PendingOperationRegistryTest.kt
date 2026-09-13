package io.gatekeeper

import io.gatekeeper.util.PendingOperationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingOperationRegistryTest {
    @Test
    fun consumesOnlyTheOperationNamedByItsCallback() {
        val registry = PendingOperationRegistry<String>()
        val first = registry.register("first")
        val second = registry.register("second")

        assertEquals("first", registry.consume(first))
        assertEquals("second", registry.consume(second))
        assertNull(registry.consume(first))
    }
}
