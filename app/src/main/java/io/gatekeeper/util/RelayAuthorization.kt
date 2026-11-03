package io.gatekeeper.util

/**
 * Гейт DummyActivity: свой процесс, своя подпись или публичное действие (S4,
 * docs/audit_android17.md). Вынесен из activity, чтобы набор действий без подписи и
 * порядок проверок держали JVM-тесты: ошибка здесь отдает права владельца профиля
 * любому приложению, умеющему послать интент. Без Android SDK.
 */
object RelayAuthorization {
    /** Неэкспортированный псевдоним DummyActivity для статуса PackageInstaller (манифест). */
    const val INSTALLER_CALLBACK_ALIAS = "io.gatekeeper.ui.PackageInstallerCallbackAlias"

    /**
     * Принимаются без подписи. Действие отсюда, открытое кросс-профильным фильтром
     * (RELAY_ACTION_FILTERS), доступно любому приложению профиля-отправителя.
     */
    val UNSIGNED_ACTIONS: Set<String> = setOf(
        ProfileActions.FINALIZE_PROVISION,
        // Подписать нечем: запрос на перепривязку и посылается потому, что
        // ключи разошлись. Гейт тут -- подтверждение человеком со сверкой кода.
        ProfileActions.REQUEST_REPAIR,
        ProfileActions.TRY_START_SERVICE,
        ProfileActions.PUBLIC_FREEZE_ALL,
        ProfileActions.PUBLIC_UNFREEZE_ALL,
        ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH,
        ProfileActions.REFRESH_MAIN_APP_LIST,
        ProfileActions.SHOW_TOAST,
        // Подпись живет 30 секунд, PendingIntent уведомления -- пока висит
        // уведомление. Действие умеет ровно одно: открыть наш же главный экран.
        ProfileActions.OPEN_MAIN_APP,
        // PACKAGEINSTALLER_CALLBACK здесь нет: подпись и nonce к нему неприменимы (PI
        // живет дольше 30-секундного окна и стреляет повторно, замер Фазы 19 на AOSP 16),
        // поэтому он проверяется по компоненту -- неэкспортированный псевдоним
        // INSTALLER_CALLBACK_ALIAS достижим только нашим PendingIntent.
    )

    /** Без подписи, но с одноразовым токеном своего процесса ([SameProcessTokens]). */
    val SAME_PROCESS_ACTIONS: Set<String> = setOf(
        ProfileActions.INSTALL_PACKAGE,
        ProfileActions.UNINSTALL_PACKAGE,
        ProfileActions.UNFREEZE_AND_LAUNCH,
    )

    /** Что известно о запросе до проверок с побочными эффектами. */
    data class Request(
        val action: String?,
        val componentClassName: String?,
        val isProfileOwner: Boolean,
        val isSettingUp: Boolean,
    )

    /**
     * [sameProcessToken] и [signature] -- проверки с побочным эффектом: токен расходуется,
     * подпись при первом обмене принимает ключ. Поэтому они ленивые и идут в этом порядке:
     * токен спрашивается только у действий своего процесса, подпись -- и у публичных
     * действий, иначе TRY_START_SERVICE и FINALIZE_PROVISION не довозили бы ключ.
     */
    fun isAuthorized(request: Request, sameProcessToken: () -> Boolean, signature: () -> Boolean): Boolean {
        val action = request.action
        return when {
            action == ProfileActions.PACKAGEINSTALLER_CALLBACK ->
                request.componentClassName == INSTALLER_CALLBACK_ALIAS
            // Фильтр рабочий -> личный висит постоянно: вне настройки любое приложение
            // профиля открывало бы личный мастер с тостом «профиль создан».
            action == ProfileActions.FINALIZE_PROVISION && !request.isProfileOwner && !request.isSettingUp ->
                false
            else -> (action in SAME_PROCESS_ACTIONS && sameProcessToken()) ||
                signature() ||
                action in UNSIGNED_ACTIONS
        }
    }
}
