package io.gatekeeper

import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.util.ServiceLiveness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceLivenessTest {
    @Test
    fun reportsReadyOnlyWhenBothBindersAreAlive() {
        assertTrue(ServiceLiveness.areAlive(Binder(), Binder()))
        assertFalse(ServiceLiveness.areAlive(Binder(), null))
        assertFalse(ServiceLiveness.areAlive(null, Binder()))
    }
}
