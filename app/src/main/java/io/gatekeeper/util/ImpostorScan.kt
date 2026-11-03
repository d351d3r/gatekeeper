package io.gatekeeper.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Поиск приложений, объявивших служебные действия Gatekeeper (S1, docs/audit_android17.md).
 *
 * Кросс-профильный интент системный форвардер пересылает без адресата и резолвит заново
 * в целевом профиле. Приложение с теми же строками действий попадает в системный выбор
 * «Открыть с помощью» рядом с Gatekeeper и, если выбрать его, получает служебный интент.
 * Владелец профиля может найти такие приложения заранее и заморозить их.
 *
 * Решение «кто посторонний» ([foreignPackages]) -- без Android SDK, проверяется JVM-тестами.
 */
object ImpostorScan {
    /** Системный форвардер и резолвер живут в пакете android -- это не подделка. */
    const val SYSTEM_PACKAGE = "android"

    /** Все действия, которые принимает DummyActivity и которые можно перехватить. */
    val RELAY_ACTIONS: List<String> = listOf(
        ProfileActions.FINALIZE_PROVISION,
        ProfileActions.START_SERVICE,
        ProfileActions.TRY_START_SERVICE,
        ProfileActions.INSTALL_PACKAGE,
        ProfileActions.UNINSTALL_PACKAGE,
        ProfileActions.UNFREEZE_AND_LAUNCH,
        ProfileActions.PUBLIC_UNFREEZE_AND_LAUNCH,
        ProfileActions.PUBLIC_FREEZE_ALL,
        ProfileActions.PUBLIC_UNFREEZE_ALL,
        ProfileActions.SHOW_TOAST,
        ProfileActions.REFRESH_MAIN_APP_LIST,
        ProfileActions.FREEZE_ALL_IN_LIST,
        ProfileActions.UNFREEZE_ALL_IN_LIST,
        ProfileActions.REMOVE_UNFREEZE_SHORTCUT,
        ProfileActions.REMOVE_UNFREEZE_SHORTCUT_2,
        ProfileActions.START_FILE_SHUTTLE,
        ProfileActions.START_FILE_SHUTTLE_2,
        ProfileActions.SYNCHRONIZE_PREFERENCE,
        ProfileActions.SYNC_ISOLATION,
        ProfileActions.SYNC_ANTI_SPY_VPN_WATCH,
        ProfileActions.SYNC_FREEZE_SERVICE,
        ProfileActions.VPN_SESSION_COMPLETE,
        ProfileActions.OPEN_POWER_SETTINGS,
        ProfileActions.OPEN_MAIN_APP,
        ProfileActions.REQUEST_REPAIR,
        ProfileActions.WIPE_PROFILE,
    )

    /**
     * Подделки в профиле, где работает [context], как "пакет|имя". Видимость пакетов
     * (Android 11+) дает их увидеть: действия перечислены в queries манифеста.
     */
    fun findInThisProfile(context: Context): List<String> {
        val pm = context.packageManager
        val handlers = RELAY_ACTIONS.flatMap { action ->
            pm.queryIntentActivities(
                Intent(action).addCategory(Intent.CATEGORY_DEFAULT),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).mapNotNull { it.activityInfo?.packageName }
        }
        return foreignPackages(handlers, context.packageName).map { pkg ->
            encode(pkg, Utility.resolveApplicationLabel(context, pkg))
        }
    }

    /** Пакеты-обработчики, кроме нашего и системного; порядок первого появления сохранен. */
    fun foreignPackages(handlerPackages: List<String>, ownPackage: String): List<String> =
        handlerPackages
            .filter { it != ownPackage && it != SYSTEM_PACKAGE }
            .distinct()

    /** Кодировка строки списка для AIDL: "пакет|имя", как у сертификатов и VPN-приложений. */
    fun encode(packageName: String, label: CharSequence): String = "$packageName|$label"

    fun decodePackage(entry: String): String = entry.substringBefore("|")

    fun decodeLabel(entry: String): String = entry.substringAfter("|", entry)
}
