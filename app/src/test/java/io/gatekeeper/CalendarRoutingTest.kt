package io.gatekeeper

import io.gatekeeper.util.CalendarRouting
import io.gatekeeper.util.CalendarRoutingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarRoutingTest {
    @Test
    fun targetCodecRoundTripsLabelsContainingSeparator() {
        val target = CalendarRoutingTarget(42L, "user@example.com", "Training | Main")
        assertEquals(target, CalendarRouting.decodeTarget(CalendarRouting.encodeTarget(target)))
        assertNull(CalendarRouting.decodeTarget("broken"))
    }
}
