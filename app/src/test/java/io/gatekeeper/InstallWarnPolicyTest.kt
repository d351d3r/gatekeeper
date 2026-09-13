package io.gatekeeper

import io.gatekeeper.util.InstallWarnPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallWarnPolicyTest {
    @Test
    fun warnsOnMiuiWorkProfileInstall() {
        assertTrue(InstallWarnPolicy.shouldWarn(isMiui = true, isProfileOwner = false))
    }

    @Test
    fun doesNotWarnOnMiuiMainProfileInstall() {
        // Зависание установщика подтверждено только в контексте рабочего
        // профиля; в основном профиле не мешаем.
        assertFalse(InstallWarnPolicy.shouldWarn(isMiui = true, isProfileOwner = true))
    }

    @Test
    fun doesNotWarnOnNonMiui() {
        assertFalse(InstallWarnPolicy.shouldWarn(isMiui = false, isProfileOwner = false))
        assertFalse(InstallWarnPolicy.shouldWarn(isMiui = false, isProfileOwner = true))
    }
}
