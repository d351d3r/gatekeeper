package io.gatekeeper

import io.gatekeeper.util.PendingIntents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A1: создатель PendingIntent отдает права на запуск из фона не устаревшим значением,
 * где оно есть (API 36+), и единственно возможным -- до того.
 */
class PendingIntentsModeTest {
    @Test
    fun allowAlwaysFromBaklava() {
        assertEquals(3, PendingIntents.creatorMode(36)) // MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        assertEquals(3, PendingIntents.creatorMode(37))
    }

    @Test
    fun legacyAllowedBeforeBaklava() {
        assertEquals(1, PendingIntents.creatorMode(34)) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        assertEquals(1, PendingIntents.creatorMode(35))
    }
}
