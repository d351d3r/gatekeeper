package io.gatekeeper

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка самого стенда, а не приложения: инструментальный прогон видит целевой пакет
 * и корректно определяет наличие рабочего профиля. Если этот класс красный, остальные
 * инструментальные тесты разбирать бессмысленно.
 */
@RunWith(AndroidJUnit4::class)
class EnvironmentTest {
    @Test
    fun instrumentationTargetsShelterPackage() {
        assertEquals(BuildConfig.APPLICATION_ID, TestProfiles.targetPackage)
    }

    @Test
    fun workProfileIsDetectedAndDistinctFromCurrentUser() {
        TestProfiles.assumeWorkProfile()
        assertTrue(TestProfiles.userProfiles.size > 1 || TestProfiles.runningInWorkProfile)
        val other = TestProfiles.otherProfile
        if (other != null) {
            assertNotEquals(Process.myUserHandle(), other)
        }
    }
}
