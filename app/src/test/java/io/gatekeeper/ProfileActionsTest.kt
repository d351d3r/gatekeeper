package io.gatekeeper

import io.gatekeeper.util.ProfileActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileActionsTest {
    @Test
    fun normalizesLegacyGatekeeperAction() {
        assertEquals(
            ProfileActions.START_SERVICE,
            ProfileActions.normalize(ProfileActions.LEGACY_START_SERVICE)
        )
    }

    @Test
    fun leavesCurrentAndUnknownActionsUntouched() {
        assertEquals(
            ProfileActions.START_SERVICE,
            ProfileActions.normalize(ProfileActions.START_SERVICE)
        )
        assertEquals("com.example.action.UNRELATED", ProfileActions.normalize("com.example.action.UNRELATED"))
    }

    @Test
    fun exposesLegacyFallbackOnlyForMigratedAction() {
        assertEquals(
            ProfileActions.LEGACY_START_SERVICE,
            ProfileActions.legacyFor(ProfileActions.START_SERVICE)
        )
        assertNull(ProfileActions.legacyFor("com.example.action.UNRELATED"))
    }

    @Test
    fun policyRegistrationCoversCurrentAndLegacyAction() {
        assertTrue(ProfileActions.variants(ProfileActions.START_SERVICE).containsAll(
            listOf(ProfileActions.START_SERVICE, ProfileActions.LEGACY_START_SERVICE)
        ))
    }

    @Test
    fun everyCurrentActionHasOneLegacyCounterpart() {
        ProfileActions.allCurrentActions.forEach { action ->
            val legacy = ProfileActions.legacyFor(action)
            assertTrue("old action is missing for $action", legacy != null)
            assertEquals(action, ProfileActions.normalize(legacy))
            assertEquals(setOf(action, legacy), ProfileActions.variants(action))
        }
    }
}
