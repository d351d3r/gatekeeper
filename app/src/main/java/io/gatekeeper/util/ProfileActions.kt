package io.gatekeeper.util

object ProfileActions {
    private const val PREFIX = "io.gatekeeper.action."

    const val FINALIZE_PROVISION = PREFIX + "FINALIZE_PROVISION"
    const val START_SERVICE = PREFIX + "START_SERVICE"
    const val TRY_START_SERVICE = PREFIX + "TRY_START_SERVICE"
    const val INSTALL_PACKAGE = PREFIX + "INSTALL_PACKAGE"
    const val UNINSTALL_PACKAGE = PREFIX + "UNINSTALL_PACKAGE"
    const val UNFREEZE_AND_LAUNCH = PREFIX + "UNFREEZE_AND_LAUNCH"
    const val PUBLIC_UNFREEZE_AND_LAUNCH = PREFIX + "PUBLIC_UNFREEZE_AND_LAUNCH"
    const val PUBLIC_FREEZE_ALL = PREFIX + "PUBLIC_FREEZE_ALL"
    const val PUBLIC_UNFREEZE_ALL = PREFIX + "PUBLIC_UNFREEZE_ALL"
    const val SHOW_TOAST = PREFIX + "SHOW_TOAST"
    const val REFRESH_MAIN_APP_LIST = PREFIX + "REFRESH_MAIN_APP_LIST"
    const val FREEZE_ALL_IN_LIST = PREFIX + "FREEZE_ALL_IN_LIST"
    const val UNFREEZE_ALL_IN_LIST = PREFIX + "UNFREEZE_ALL_IN_LIST"
    const val REMOVE_UNFREEZE_SHORTCUT = PREFIX + "REMOVE_UNFREEZE_SHORTCUT"

    /**
     * Та же операция в обратную сторону. Два действия вместо одного в обе стороны:
     * иначе форварднутый интент резолвится на той стороне дважды -- в свою
     * DummyActivity и в форвардер обратно -- и пользователь получает системный
     * выбор профиля вместо тихого удаления ярлыка. Тот же прием, что у
     * START_FILE_SHUTTLE / START_FILE_SHUTTLE_2.
     */
    const val REMOVE_UNFREEZE_SHORTCUT_2 = PREFIX + "REMOVE_UNFREEZE_SHORTCUT_2"
    const val START_FILE_SHUTTLE = PREFIX + "START_FILE_SHUTTLE"
    const val START_FILE_SHUTTLE_2 = PREFIX + "START_FILE_SHUTTLE_2"
    const val SYNCHRONIZE_PREFERENCE = PREFIX + "SYNCHRONIZE_PREFERENCE"

    /**
     * Весь набор политик изоляции одним письмом. По одной настройке за раз не
     * годится: реле запускает activity, двадцать три запуска подряд сталкиваются
     * друг с другом, долетают единицы, а применение тут же сбрасывает остальные.
     */
    const val SYNC_ISOLATION = PREFIX + "SYNC_ISOLATION"

    /** Список включенных политик внутри SYNC_ISOLATION. */
    const val EXTRA_ISOLATION_KEYS = "isolation_keys"
    const val SYNC_ANTI_SPY_VPN_WATCH = PREFIX + "SYNC_ANTI_SPY_VPN_WATCH"
    /** Поднять/остановить FreezeService по факту настроек единой заморозки по блокировке. */
    const val SYNC_FREEZE_SERVICE = PREFIX + "SYNC_FREEZE_SERVICE"
    const val VPN_SESSION_COMPLETE = PREFIX + "VPN_SESSION_COMPLETE"
    const val PACKAGEINSTALLER_CALLBACK = PREFIX + "PACKAGEINSTALLER_CALLBACK"
    const val BATCH_FREEZE_ALL = PREFIX + "BATCH_FREEZE_ALL"
    const val BATCH_UNFREEZE_ALL = PREFIX + "BATCH_UNFREEZE_ALL"
    const val SHOW_BATCH_TOAST = PREFIX + "SHOW_BATCH_TOAST"
    const val REFRESH_APP_LISTS = PREFIX + "REFRESH_APP_LISTS"
    const val OPEN_POWER_SETTINGS = PREFIX + "OPEN_POWER_SETTINGS"

    /** Нажатие на уведомление рабочего профиля: главный экран есть только в личном. */
    const val OPEN_MAIN_APP = PREFIX + "OPEN_MAIN_APP"

    /**
     * Личная копия просит рабочую принять ее новый ключ подписи реле. Нужна после
     * переустановки личной копии: ее данные стираются вместе с общим ключом, и без
     * перепривязки профиль остается недостижимым навсегда (ProfileRepairFlow).
     */
    const val REQUEST_REPAIR = PREFIX + "REQUEST_REPAIR"

    /**
     * Паник-удаление рабочего профиля. Личная сторона после подтверждения шлет
     * подписанное действие рабочей DummyActivity, а та как profile owner зовет
     * DevicePolicyManager.wipeData -- уносит только управляемый профиль, не устройство.
     */
    const val WIPE_PROFILE = PREFIX + "WIPE_PROFILE"
}
