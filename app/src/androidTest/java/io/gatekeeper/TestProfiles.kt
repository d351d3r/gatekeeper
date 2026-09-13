package io.gatekeeper

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * Предусловия инструментальных тестов. Часть сценариев (провайдер SAF, реле, кросс-профильные
 * операции) имеет смысл только при поднятом рабочем профиле; на голом устройстве такие тесты
 * должны пропускаться, а не падать.
 *
 * Стенд готовится скриптом `tools/testbench.sh`.
 */
object TestProfiles {
    val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val targetPackage: String
        get() = targetContext.packageName

    /** Профили, видимые вызывающему пользователю, включая его самого. */
    val userProfiles: List<UserHandle> by lazy {
        targetContext.getSystemService(UserManager::class.java)?.userProfiles ?: emptyList()
    }

    /** Тест исполняется внутри рабочего профиля: приложение там -- владелец профиля. */
    val runningInWorkProfile: Boolean by lazy {
        targetContext.getSystemService(DevicePolicyManager::class.java)
            ?.isProfileOwnerApp(targetPackage) == true
    }

    val workProfilePresent: Boolean by lazy { runningInWorkProfile || userProfiles.size > 1 }

    /** Хэндл профиля, отличного от текущего; null, если рабочего профиля нет. */
    val otherProfile: UserHandle? by lazy {
        userProfiles.firstOrNull { it != Process.myUserHandle() }
    }

    fun assumeWorkProfile() {
        Assume.assumeTrue(
            "рабочий профиль не поднят: tools/testbench.sh profile",
            workProfilePresent
        )
    }

    /** Для тестов, которые обращаются через границу профиля со стороны личного. */
    fun assumeRunningInPersonalProfileWithWorkProfile() {
        assumeWorkProfile()
        Assume.assumeFalse("прогон идет внутри рабочего профиля", runningInWorkProfile)
        Assume.assumeNotNull(otherProfile)
    }
}
