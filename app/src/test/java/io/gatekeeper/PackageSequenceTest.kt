package io.gatekeeper

import io.gatekeeper.util.PackageSequence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageSequenceTest {

    @Test
    fun `first answer is a baseline, not a change`() {
        assertFalse(PackageSequence.changed(null, 17))
        assertFalse(PackageSequence.changed(null, PackageSequence.FROM_SCRATCH))
    }

    @Test
    fun `same number means nothing changed`() {
        assertFalse(PackageSequence.changed(17, 17))
    }

    @Test
    fun `a different number is a change`() {
        assertTrue(PackageSequence.changed(17, 18))
    }

    /** Номер сбрасывается перезагрузкой: уход назад -- тоже изменение состава. */
    @Test
    fun `a number going backwards counts as a change`() {
        assertTrue(PackageSequence.changed(42, 3))
    }
}
