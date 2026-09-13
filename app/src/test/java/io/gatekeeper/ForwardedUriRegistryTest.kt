package io.gatekeeper

import io.gatekeeper.util.ForwardedUriRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardedUriRegistryTest {
    @Test
    fun keepsConcurrentForwardsSeparateAndClearsOnlyTheRequestedOne() {
        val registry = ForwardedUriRegistry<String>()
        val firstPath = registry.register("first", "apk")
        val secondPath = registry.register("second", "apk")

        registry.remove(firstPath)

        assertNull(registry.get(firstPath))
        assertEquals("second", registry.get(secondPath))
    }
}
