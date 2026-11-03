package io.gatekeeper

import io.gatekeeper.util.ProfileActions
import io.gatekeeper.util.RelayAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт DummyActivity (S4). DummyActivity экспортирована и в профиле владеет правами
 * владельца профиля, поэтому любая ошибка здесь -- установка, разморозка или стирание
 * профиля по интенту чужого приложения.
 */
class RelayAuthorizationTest {
    private class Checks(private val token: Boolean = false, private val signed: Boolean = false) {
        var tokenAsked = 0
        var signatureAsked = 0

        fun authorize(request: RelayAuthorization.Request): Boolean =
            RelayAuthorization.isAuthorized(
                request,
                sameProcessToken = { tokenAsked++; token },
                signature = { signatureAsked++; signed },
            )
    }

    private fun request(
        action: String?,
        component: String? = "io.gatekeeper.ui.DummyActivity",
        isProfileOwner: Boolean = true,
        isSettingUp: Boolean = false,
    ) = RelayAuthorization.Request(action, component, isProfileOwner, isSettingUp)

    /**
     * Набор действий без подписи -- поверхность для любого приложения. Расширять его
     * можно только осознанно: тест меняется вместе с набором.
     */
    @Test
    fun unsignedSurfaceIsExactlyTheHarmlessActions() {
        assertEquals(
            setOf(
                ProfileActions.FINALIZE_PROVISION,
                ProfileActions.REQUEST_REPAIR,
                ProfileActions.TRY_START_SERVICE,
                ProfileActions.PUBLIC_FREEZE_ALL,
                ProfileActions.PUBLIC_UNFREEZE_ALL,
                ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH,
                ProfileActions.REFRESH_MAIN_APP_LIST,
                ProfileActions.SHOW_TOAST,
                ProfileActions.OPEN_MAIN_APP,
            ),
            RelayAuthorization.UNSIGNED_ACTIONS,
        )
        assertEquals(
            setOf(ProfileActions.INSTALL_PACKAGE, ProfileActions.UNINSTALL_PACKAGE, ProfileActions.UNFREEZE_AND_LAUNCH),
            RelayAuthorization.SAME_PROCESS_ACTIONS,
        )
    }

    @Test
    fun privilegedActionsNeedSignatureOrToken() {
        val privileged = listOf(
            ProfileActions.START_SERVICE,
            ProfileActions.INSTALL_PACKAGE,
            ProfileActions.UNINSTALL_PACKAGE,
            ProfileActions.UNFREEZE_AND_LAUNCH,
            ProfileActions.FREEZE_ALL_IN_LIST,
            ProfileActions.UNFREEZE_ALL_IN_LIST,
            ProfileActions.SYNCHRONIZE_PREFERENCE,
            ProfileActions.SYNC_ISOLATION,
            ProfileActions.START_FILE_SHUTTLE,
            ProfileActions.WIPE_PROFILE,
            null,
        )
        for (action in privileged) {
            assertFalse("$action прошел без подписи", Checks().authorize(request(action)))
            assertTrue("$action не прошел с подписью", Checks(signed = true).authorize(request(action)))
        }
    }

    /** Статус установщика -- только через неэкспортированный псевдоним; подпись не спасает. */
    @Test
    fun installerCallbackIsAcceptedOnlyThroughTheAlias() {
        val callback = ProfileActions.PACKAGEINSTALLER_CALLBACK
        val viaAlias = Checks()
        assertTrue(viaAlias.authorize(request(callback, RelayAuthorization.INSTALLER_CALLBACK_ALIAS)))
        assertEquals(0, viaAlias.signatureAsked)

        assertFalse(Checks(signed = true).authorize(request(callback)))
        assertFalse(Checks(signed = true).authorize(request(callback, component = null)))
    }

    /** Фильтр рабочий -> личный висит постоянно: вне настройки мастер в личном не открывается. */
    @Test
    fun finalizeProvisionReachesThePersonalSideOnlyDuringSetup() {
        val outsideSetup = Checks(signed = true)
        assertFalse(outsideSetup.authorize(request(ProfileActions.FINALIZE_PROVISION, isProfileOwner = false)))
        assertEquals(0, outsideSetup.signatureAsked)

        val duringSetup = request(ProfileActions.FINALIZE_PROVISION, isProfileOwner = false, isSettingUp = true)
        assertTrue(Checks().authorize(duringSetup))
        assertTrue(Checks().authorize(request(ProfileActions.FINALIZE_PROVISION, isProfileOwner = true)))
    }

    @Test
    fun sameProcessTokenOpensOnlyItsActions() {
        assertTrue(Checks(token = true).authorize(request(ProfileActions.INSTALL_PACKAGE)))

        val foreignAction = Checks(token = true)
        assertFalse(foreignAction.authorize(request(ProfileActions.WIPE_PROFILE)))
        assertEquals("токен чужого действия не должен расходоваться", 0, foreignAction.tokenAsked)
    }

    /** Подпись проверяется и у публичных действий: иначе первый обмен не довозил бы ключ. */
    @Test
    fun signatureCheckRunsForUnsignedBootstrapActions() {
        for (action in listOf(ProfileActions.TRY_START_SERVICE, ProfileActions.FINALIZE_PROVISION)) {
            val checks = Checks()
            assertTrue(checks.authorize(request(action)))
            assertEquals("$action: ключ не будет принят", 1, checks.signatureAsked)
        }
    }

    @Test
    fun validTokenSkipsTheSignatureCheck() {
        val checks = Checks(token = true)
        assertTrue(checks.authorize(request(ProfileActions.UNFREEZE_AND_LAUNCH)))
        assertEquals(0, checks.signatureAsked)
    }
}
