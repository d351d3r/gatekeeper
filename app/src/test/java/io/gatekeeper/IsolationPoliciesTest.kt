package io.gatekeeper

import io.gatekeeper.util.IsolationPolicies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolationPoliciesTest {

    @Test
    fun `convenient preset turns everything off`() {
        val preset = IsolationPolicies.preset(isolated = false)
        assertEquals(IsolationPolicies.ALL.size, preset.size)
        assertTrue(preset.values.none { it })
    }

    /** «Изолированно» не должно ломать обычную работу клона. */
    @Test
    fun `isolated preset leaves camera, calls and installs alone`() {
        val preset = IsolationPolicies.preset(isolated = true)
        assertFalse(preset.getValue(IsolationPolicies.METHOD_CAMERA))
        assertFalse(preset.getValue(IsolationPolicies.METHOD_SCREEN_CAPTURE))
        assertFalse(preset.getValue("no_outgoing_calls"))
        assertFalse(preset.getValue("no_install_apps"))
    }

    @Test
    fun `isolated preset closes the leaks it exists for`() {
        val preset = IsolationPolicies.preset(isolated = true)
        assertTrue(preset.getValue("no_cross_profile_copy_paste"))
        assertTrue(preset.getValue("no_assist_content"))
        assertTrue(preset.getValue(IsolationPolicies.METHOD_BACKUP_OFF))
        assertTrue(preset.getValue(IsolationPolicies.METHOD_PERMISSION_AUTO_DENY))
    }

    @Test
    fun `a preset recognises itself`() {
        assertEquals(false, IsolationPolicies.matchedPreset(IsolationPolicies.preset(false)))
        assertEquals(true, IsolationPolicies.matchedPreset(IsolationPolicies.preset(true)))
    }

    @Test
    fun `a hand-made set matches no preset`() {
        val mixed = IsolationPolicies.preset(false).toMutableMap()
        mixed[IsolationPolicies.METHOD_CAMERA] = true
        assertNull(IsolationPolicies.matchedPreset(mixed))
    }

    /** Ключ настройки должен быть узнаваем в дампе хранилища. */
    @Test
    fun `pref names are prefixed`() {
        assertEquals("isolation_no_assist_content", IsolationPolicies.prefName("no_assist_content"))
    }

    @Test
    fun `policy keys are unique`() {
        assertEquals(IsolationPolicies.ALL.size, IsolationPolicies.ALL.map { it.key }.toSet().size)
    }
}
