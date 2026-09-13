package io.gatekeeper

import io.gatekeeper.util.ProfileActions
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileActionsTest {
    /** Все строки действий живут на одном префиксе пакета; чужие домены в действия недопустимы. */
    @Test
    fun everyActionUsesApplicationPrefix() {
        val prefix = "io.gatekeeper.action."
        val actions = listOf(
            ProfileActions.FINALIZE_PROVISION, ProfileActions.START_SERVICE,
            ProfileActions.TRY_START_SERVICE, ProfileActions.INSTALL_PACKAGE,
            ProfileActions.UNINSTALL_PACKAGE, ProfileActions.UNFREEZE_AND_LAUNCH,
            ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH, ProfileActions.UNFREEZE_APP,
            ProfileActions.PUBLIC_FREEZE_ALL, ProfileActions.PUBLIC_UNFREEZE_ALL,
            ProfileActions.SHOW_TOAST, ProfileActions.REFRESH_MAIN_APP_LIST,
            ProfileActions.FREEZE_ALL_IN_LIST, ProfileActions.UNFREEZE_ALL_IN_LIST,
            ProfileActions.REMOVE_UNFREEZE_SHORTCUT, ProfileActions.START_FILE_SHUTTLE,
            ProfileActions.START_FILE_SHUTTLE_2, ProfileActions.SYNCHRONIZE_PREFERENCE,
            ProfileActions.SYNC_ANTI_SPY_VPN_WATCH, ProfileActions.VPN_SESSION_COMPLETE,
            ProfileActions.PACKAGEINSTALLER_CALLBACK, ProfileActions.BATCH_FREEZE_ALL,
            ProfileActions.BATCH_UNFREEZE_ALL, ProfileActions.SHOW_BATCH_TOAST,
            ProfileActions.REFRESH_APP_LISTS, ProfileActions.OPEN_POWER_SETTINGS
        )
        actions.forEach { action ->
            assertTrue("action без префикса пакета: $action", action.startsWith(prefix))
        }
    }
}
