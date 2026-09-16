package io.gatekeeper.util

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.Settings
import io.gatekeeper.receivers.AppListRefreshReceiver
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.services.BatchFreezeService
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.ui.MainActivity
import io.gatekeeper.widget.FreezeWidgetProvider

// Направления проверены замером форвардеров (query-activities, API 36): у флагов
// имя обратно фактическому направлению, поэтому и константы, и записи таблицы ниже
// ориентируемся на замер, а не на имя флага.

/** Личный вызывает действие, резолвящееся в рабочем (личный -> рабочий): поднимает
 *  ForwardIntentToManagedProfile в личном профиле. Имя флага обратно смыслу. */
private const val TO_PARENT = DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT

/** Рабочий вызывает действие, резолвящееся в личном (рабочий -> личный): поднимает
 *  ForwardIntentToParent в рабочем профиле. Имя флага обратно смыслу. */
private const val TO_WORK = DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED

private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"

/**
 * Служебные фильтры релея: action -> направление. Единая таблица вместо
 * ~30 повторных вызовов addCrossProfileIntentFilter. Направление не всегда
 * очевидно из имени действия — сомнительные места помечены комментариями.
 */
private val RELAY_ACTION_FILTERS: List<Pair<String, Int>> = listOf(
    DummyActivity.START_SERVICE to TO_PARENT,
    DummyActivity.REQUEST_REPAIR to TO_PARENT,
    DummyActivity.TRY_START_SERVICE to TO_PARENT,
    DummyActivity.UNFREEZE_AND_LAUNCH to TO_PARENT,
    DummyActivity.FREEZE_ALL_IN_LIST to TO_PARENT,
    DummyActivity.UNFREEZE_ALL_IN_LIST to TO_PARENT,

    // Тост + обновление списков после фоновой заморозки по VPN: исполнитель в
    // рабочем профиле поверхности их на личном (work -> personal).
    DummyActivity.SHOW_TOAST to TO_WORK,

    // Только одно направление, рабочий -> личный (у флага имя наоборот, см. I5-bis).
    // С регистрацией в обе стороны форварднутый интент приезжает в личный профиль и
    // резолвится там в двух целях сразу: своя DummyActivity и форвардер обратно в
    // рабочий. Вместо тихого обновления списка система показывает пользователю выбор
    // «Complete action using / Personal | Work» (замер на AOSP 16:
    // START u11 ... act=REFRESH_MAIN_APP_LIST cmp=android/...ResolverActivity).
    // Личный профиль это действие через реле не шлет: там компонент задан явно
    // (ProfileNotifier.refreshMainAppListIntent, fromWork=false).
    DummyActivity.REFRESH_MAIN_APP_LIST to TO_WORK,
    AppListRefreshReceiver.ACTION to TO_WORK,
    AppListRefreshReceiver.ACTION to TO_PARENT,
    MainActivity.ACTION_REFRESH_APP_LISTS to TO_WORK,
    MainActivity.ACTION_REFRESH_APP_LISTS to TO_PARENT,
    // По одному направлению на действие -- см. комментарий у REFRESH_MAIN_APP_LIST.
    DummyActivity.REMOVE_UNFREEZE_SHORTCUT to TO_WORK,
    DummyActivity.REMOVE_UNFREEZE_SHORTCUT_2 to TO_PARENT,
    DummyActivity.PUBLIC_FREEZE_ALL to TO_WORK,
    DummyActivity.PUBLIC_UNFREEZE_ALL to TO_WORK,
    DummyActivity.FINALIZE_PROVISION to TO_WORK,
    DummyActivity.START_FILE_SHUTTLE to TO_PARENT,
    DummyActivity.START_FILE_SHUTTLE_2 to TO_WORK,
    DummyActivity.SYNCHRONIZE_PREFERENCE to TO_PARENT,
    DummyActivity.SYNC_ISOLATION to TO_PARENT,

    // Личный -> рабочий, несмотря на имя: прежний флаг не резолвился
    // и спамил IllegalStateException.
    DummyActivity.SYNC_ANTI_SPY_VPN_WATCH to TO_PARENT,

    // Единый владелец триггера блокировки: настройка в личном профиле шлёт
    // SYNC_FREEZE_SERVICE в рабочий, где FreezeService и поднимается/падает.
    DummyActivity.SYNC_FREEZE_SERVICE to TO_PARENT,

    // Рабочий -> личный (см. CrossProfileScheduler.scheduleVpnSessionCompleteOnMainProfile):
    // тот же work->personal форвард, что и SHOW_TOAST. С MANAGED_CAN_ACCESS_PARENT
    // личная сторона не видит форвардер и молча спамила "no system forwarder"
    // (стенд AOSP 16, полная заморозка списка).
    DummyActivity.VPN_SESSION_COMPLETE to TO_WORK,

    DummyActivity.OPEN_POWER_SETTINGS to TO_PARENT,
    // Рабочий -> личный, как SHOW_TOAST и VPN_SESSION_COMPLETE: именно этот флаг
    // поднимает ForwardIntentToParent в рабочем профиле (замер на AOSP 16,
    // с TO_PARENT резолвилась только своя DummyActivity).
    DummyActivity.OPEN_MAIN_APP to TO_WORK,
    BatchFreezeService.ACTION to TO_WORK,
    DummyActivity.INSTALL_PACKAGE to TO_PARENT,
    DummyActivity.UNINSTALL_PACKAGE to TO_PARENT,
    // Личный (подтверждение паника) -> рабочий, где profile owner зовет wipeData.
    // TO_PARENT, как у INSTALL_PACKAGE и SYNC_ISOLATION: у флага имя наоборот, но
    // именно он поднимает ForwardIntentToManagedProfile (личный -> рабочий, замер).
    DummyActivity.WIPE_PROFILE to TO_PARENT,
)

private fun actionSendFilter(): IntentFilter = IntentFilter().apply {
    addAction(Intent.ACTION_SEND)
    addAction(Intent.ACTION_SEND_MULTIPLE)
    try {
        addDataType("*/*")
    } catch (_: IntentFilter.MalformedMimeTypeException) {
    }
    addCategory(Intent.CATEGORY_DEFAULT)
}

private fun contentViewFilter(): IntentFilter = IntentFilter().apply {
    addAction(Intent.ACTION_VIEW)
    addAction(Intent.ACTION_EDIT)
    addAction(Intent.ACTION_PICK)
    addCategory(Intent.CATEGORY_DEFAULT)
    addCategory(Intent.CATEGORY_BROWSABLE)
    addDataScheme("content")
    try {
        addDataType("*/*")
    } catch (_: IntentFilter.MalformedMimeTypeException) {
    }
}

private fun contentSelectionFilter(): IntentFilter = IntentFilter().apply {
    addAction(Intent.ACTION_GET_CONTENT)
    addAction(Intent.ACTION_OPEN_DOCUMENT)
    addAction(Intent.ACTION_CREATE_DOCUMENT)
    addCategory(Intent.CATEGORY_DEFAULT)
    addCategory(Intent.CATEGORY_OPENABLE)
    try {
        addDataType("*/*")
    } catch (_: IntentFilter.MalformedMimeTypeException) {
    }
}

private fun documentTreeFilter(): IntentFilter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addCategory(Intent.CATEGORY_DEFAULT)
}

private fun photoPickerFilter(): IntentFilter = IntentFilter(ACTION_PICK_IMAGES).apply {
    addCategory(Intent.CATEGORY_DEFAULT)
    try {
        addDataType("*/*")
    } catch (_: IntentFilter.MalformedMimeTypeException) {
    }
}

private fun crossProfileContentFilters(): List<IntentFilter> = listOf(
    contentViewFilter(),
    contentSelectionFilter(),
    documentTreeFilter(),
    photoPickerFilter(),
)

private fun calendarInsertFilter(): IntentFilter = IntentFilter().apply {
    addAction(Intent.ACTION_INSERT)
    addCategory(Intent.CATEGORY_DEFAULT)
    addDataScheme("content")
    addDataAuthority(CalendarContract.AUTHORITY, null)
    try {
        addDataType("vnd.android.cursor.dir/event")
    } catch (_: IntentFilter.MalformedMimeTypeException) {
    }
}

private fun browsableFilter(withDefaultCategory: Boolean): IntentFilter =
    IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (withDefaultCategory) {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        addDataScheme("http")
        addDataScheme("https")
    }

private fun buildLinkViewFilter(rule: String): IntentFilter =
    IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addDataScheme("http")
        addDataScheme("https")
        for (host in CrossProfileLinkRules.hostPatterns(rule)) {
            addDataAuthority(host, null)
        }
    }

/**
 * Единый владелец набора кросс-профильных intent-фильтров (см. план, A4):
 * стирает весь набор через clearCrossProfileIntentFilters и собирает заново
 * из таблицы [RELAY_ACTION_FILTERS] плюс доменные правила ссылок из префов
 * (C2). Никто больше фильтры не трогает — иначе добавление одного правила
 * сносило бы весь релей между профилями. Вынесено из Utility.
 */
object WorkProfilePolicy {

    /**
     * POST_NOTIFICATIONS для рабочей копии выдаем как владелец профиля, без диалога.
     * Иначе система спрашивает разрешение на уведомления второй раз (первый запрос --
     * в личном профиле), и пользователь жмет «Разрешить» дважды за одно и то же.
     * В личном профиле так выдать нельзя: там владельца нет, запрос остается один.
     */
    private fun grantWorkNotifications(
        context: Context,
        manager: DevicePolicyManager,
        adminComponent: ComponentName,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        manager.setPermissionGrantState(
            adminComponent,
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
        )
    }

    /**
     * Весь набор кросс-профильных intent-фильтров разом: стирается и собирается заново,
     * потому что этот метод -- единственный владелец набора (добавление из сервиса иначе
     * стерло бы релей). Служебные фильтры + opt-in отправка рабочее->личное (шара) +
     * доменные правила ссылок (C2).
     */
    private fun applyCrossProfileIntentFilters(
        manager: DevicePolicyManager,
        adminComponent: ComponentName
    ) {
        manager.clearCrossProfileIntentFilters(adminComponent)

        for ((action, direction) in RELAY_ACTION_FILTERS) {
            manager.addCrossProfileIntentFilter(adminComponent, IntentFilter(action), direction)
        }

        manager.addCrossProfileIntentFilter(adminComponent, actionSendFilter(), TO_WORK)
        // Обратное направление -- отправка (ACTION_SEND) из рабочего в личное -- только по
        // явному тумблеру: в шаре рабочего приложения появляется вкладка «Личные». Дырка в
        // изоляции по действию пользователя, поэтому выключено по умолчанию. Входящий
        // доступ к рабочим файлам не открывается -- добавляется лишь форвард шары.
        if (SettingsManager.getInstance().getShareWorkToPersonalEnabled()) {
            manager.addCrossProfileIntentFilter(adminComponent, actionSendFilter(), TO_PARENT)
        }
        if (SettingsManager.getInstance().getCrossProfileContentAccessEnabled()) {
            for (filter in crossProfileContentFilters()) {
                manager.addCrossProfileIntentFilter(adminComponent, filter, TO_WORK)
                manager.addCrossProfileIntentFilter(adminComponent, filter, TO_PARENT)
            }
        }
        if (SettingsManager.getInstance().getCreatePersonalCalendarEventsEnabled()) {
            manager.addCrossProfileIntentFilter(
                adminComponent,
                calendarInsertFilter(),
                TO_WORK,
            )
        }
        manager.addCrossProfileIntentFilter(
            adminComponent,
            browsableFilter(withDefaultCategory = false),
            TO_WORK
        )
        manager.addCrossProfileIntentFilter(
            adminComponent,
            browsableFilter(withDefaultCategory = true),
            TO_WORK
        )

        // Обратное направление для ссылок -- личный -> рабочий: приложения рабочего
        // профиля обрабатывают http(s)-ссылку, нажатую в личном. Дырка в изоляции по
        // действию пользователя, поэтому за тумблером и выключено по умолчанию. Флаг
        // TO_PARENT (FLAG_MANAGED_CAN_ACCESS_PARENT) поднимает ForwardIntentToManagedProfile
        // в личном профиле -- проверено замером форвардеров (query-activities, API 36);
        // имя константы обратно смыслу, см. заметку у определения TO_PARENT/TO_WORK.
        if (SettingsManager.getInstance().getOpenLinksInWorkEnabled()) {
            manager.addCrossProfileIntentFilter(
                adminComponent,
                browsableFilter(withDefaultCategory = false),
                TO_PARENT
            )
            manager.addCrossProfileIntentFilter(
                adminComponent,
                browsableFilter(withDefaultCategory = true),
                TO_PARENT
            )
        }

        // C2: доменные правила перенаправления ссылок (docs/feature_cross_profile_links.md).
        val linkRules = LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES)
        for (rule in linkRules) {
            manager.addCrossProfileIntentFilter(adminComponent, buildLinkViewFilter(rule), TO_PARENT)
        }
    }

    fun applyAllCrossProfilePackages(
        context: Context,
        manager: DevicePolicyManager,
        adminComponent: ComponentName,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val packageFlags = PackageManager.GET_PERMISSIONS or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        val packages = context.packageManager.getInstalledPackages(packageFlags).asSequence()
            .filter { it.packageName != context.packageName }
            .filter { packageInfo ->
                (packageInfo.applicationInfo?.flags ?: 0) and
                    android.content.pm.ApplicationInfo.FLAG_INSTALLED != 0
            }
            .filter {
                it.requestedPermissions?.contains(Manifest.permission.INTERACT_ACROSS_PROFILES) == true
            }
            .map { it.packageName }
            .toSet()
        manager.setCrossProfilePackages(adminComponent, packages)
        return true
    }

    fun enforceWorkProfilePolicies(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(
            context.applicationContext,
            GatekeeperDeviceAdminReceiver::class.java
        )

        grantWorkNotifications(context, manager, adminComponent)

        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.applicationContext, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0
        )

        // Виджет заморозки живет в личном профиле: счетчики туда кладет главный
        // экран, которого здесь нет. Иначе он предлагается во вкладке «Рабочие»
        // в списке виджетов и показывает пустое состояние.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.applicationContext, FreezeWidgetProvider::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0
        )

        applyCrossProfileIntentFilters(manager, adminComponent)

        if (SettingsManager.getInstance().getCrossProfileInteractionAllEnabled()) {
            applyAllCrossProfilePackages(context, manager, adminComponent)
        }

        manager.setCrossProfileContactsSearchDisabled(
            adminComponent,
            SettingsManager.getInstance().getBlockContactsSearchingEnabled()
        )

        // Парная политика: без нее номер, сохраненный только в профиле, все равно
        // подписывается именем в журнале личного профиля.
        manager.setCrossProfileCallerIdDisabled(
            adminComponent,
            SettingsManager.getInstance().getBlockCallerIdEnabled()
        )

        // Политики изоляции применяются здесь, а не в enforceUserRestrictions:
        // после смены настройки реле зовет именно этот метод (WorkRelayHandlers),
        // и без этого пресет долетал до профиля, но не применялся.
        IsolationEnforcer.apply(context)

        manager.setProfileEnabled(adminComponent)
    }

    fun enforceUserRestrictions(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(
            context.applicationContext,
            GatekeeperDeviceAdminReceiver::class.java
        )
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            manager.setSecureSetting(
                adminComponent,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                "1"
            )
        }

        manager.addUserRestriction(adminComponent, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING)
    }
}
