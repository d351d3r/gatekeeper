package io.gatekeeper.util

object ProfileActions {
    private const val PREFIX = "net.typeblog.gatekeeper.action."
    private const val LEGACY_PREFIX = "net.typeblog.shelter.action."

    const val FINALIZE_PROVISION = PREFIX + "FINALIZE_PROVISION"
    const val START_SERVICE = PREFIX + "START_SERVICE"
    const val TRY_START_SERVICE = PREFIX + "TRY_START_SERVICE"
    const val INSTALL_PACKAGE = PREFIX + "INSTALL_PACKAGE"
    const val UNINSTALL_PACKAGE = PREFIX + "UNINSTALL_PACKAGE"
    const val UNFREEZE_AND_LAUNCH = PREFIX + "UNFREEZE_AND_LAUNCH"
    const val PUBLIC_UNFREEZE_AND_LAUNCH = PREFIX + "PUBLIC_UNFREEZE_AND_LAUNCH"
    const val UNFREEZE_APP = PREFIX + "UNFREEZE_APP"
    const val PUBLIC_FREEZE_ALL = PREFIX + "PUBLIC_FREEZE_ALL"
    const val PUBLIC_UNFREEZE_ALL = PREFIX + "PUBLIC_UNFREEZE_ALL"
    const val SHOW_TOAST = PREFIX + "SHOW_TOAST"
    const val REFRESH_MAIN_APP_LIST = PREFIX + "REFRESH_MAIN_APP_LIST"
    const val FREEZE_ALL_IN_LIST = PREFIX + "FREEZE_ALL_IN_LIST"
    const val UNFREEZE_ALL_IN_LIST = PREFIX + "UNFREEZE_ALL_IN_LIST"
    const val ENABLE_AUTO_FREEZE_WORK_PROFILE = PREFIX + "ENABLE_AUTO_FREEZE_WORK_PROFILE"
    const val REMOVE_UNFREEZE_SHORTCUT = PREFIX + "REMOVE_UNFREEZE_SHORTCUT"
    const val START_FILE_SHUTTLE = PREFIX + "START_FILE_SHUTTLE"
    const val START_FILE_SHUTTLE_2 = PREFIX + "START_FILE_SHUTTLE_2"
    const val SYNCHRONIZE_PREFERENCE = PREFIX + "SYNCHRONIZE_PREFERENCE"
    const val SYNC_ANTI_SPY_VPN_WATCH = PREFIX + "SYNC_ANTI_SPY_VPN_WATCH"
    const val VPN_SESSION_COMPLETE = PREFIX + "VPN_SESSION_COMPLETE"
    const val PACKAGEINSTALLER_CALLBACK = PREFIX + "PACKAGEINSTALLER_CALLBACK"
    const val BATCH_FREEZE_ALL = PREFIX + "BATCH_FREEZE_ALL"
    const val BATCH_UNFREEZE_ALL = PREFIX + "BATCH_UNFREEZE_ALL"
    const val SHOW_BATCH_TOAST = PREFIX + "SHOW_BATCH_TOAST"
    const val REFRESH_APP_LISTS = PREFIX + "REFRESH_APP_LISTS"

    const val LEGACY_START_SERVICE = LEGACY_PREFIX + "START_SERVICE"

    private val currentActions = setOf(
        FINALIZE_PROVISION, START_SERVICE, TRY_START_SERVICE, INSTALL_PACKAGE, UNINSTALL_PACKAGE,
        UNFREEZE_AND_LAUNCH, PUBLIC_UNFREEZE_AND_LAUNCH, UNFREEZE_APP, PUBLIC_FREEZE_ALL,
        PUBLIC_UNFREEZE_ALL, SHOW_TOAST, REFRESH_MAIN_APP_LIST, FREEZE_ALL_IN_LIST,
        UNFREEZE_ALL_IN_LIST, ENABLE_AUTO_FREEZE_WORK_PROFILE, REMOVE_UNFREEZE_SHORTCUT,
        START_FILE_SHUTTLE, START_FILE_SHUTTLE_2, SYNCHRONIZE_PREFERENCE,
        SYNC_ANTI_SPY_VPN_WATCH, VPN_SESSION_COMPLETE, PACKAGEINSTALLER_CALLBACK,
        BATCH_FREEZE_ALL, BATCH_UNFREEZE_ALL, SHOW_BATCH_TOAST, REFRESH_APP_LISTS
    )

    internal val allCurrentActions: Set<String> = currentActions

    private val legacyByCurrent = currentActions.associateWith { current ->
        LEGACY_PREFIX + current.removePrefix(PREFIX)
    }
    private val currentByLegacy = legacyByCurrent.entries.associate { (current, legacy) ->
        legacy to current
    }

    fun normalize(action: String?): String? = currentByLegacy[action] ?: action

    fun legacyFor(action: String?): String? = legacyByCurrent[action]

    fun variants(action: String): Set<String> =
        legacyByCurrent[action]?.let { linkedSetOf(action, it) } ?: setOf(action)
}
