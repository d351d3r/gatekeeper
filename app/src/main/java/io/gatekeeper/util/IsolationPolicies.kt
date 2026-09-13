package io.gatekeeper.util

/**
 * Набор политик изоляции профиля и два пресета поверх него.
 *
 * Состав проверен на стенде (AVD 16, пробник в debug-сборке): сюда попало только
 * то, что владельцу **личного** рабочего профиля реально дают применить. За
 * бортом остались `no_sms`, `disallow_camera_toggle`, `disallow_microphone_toggle`
 * (платформа отвечает "Profile owner cannot set user restriction") и
 * `setKeyguardDisabledFeatures` (вызов проходит, эффекта нет).
 *
 * Здесь только описание и арифметика пресетов, без Android API: так состав
 * проверяется юнит-тестами, а применение живет в [WorkProfilePolicy].
 */
object IsolationPolicies {

    /** Ограничение пользователя профиля: ключ из UserManager. */
    const val KIND_RESTRICTION = 0

    /** Отдельный вызов DevicePolicyManager. */
    const val KIND_METHOD = 1

    /** Идентификаторы вызовов; ключи ограничений берутся из UserManager как есть. */
    const val METHOD_CAMERA = "method_camera_disabled"
    const val METHOD_SCREEN_CAPTURE = "method_screen_capture_disabled"
    const val METHOD_PERMISSION_AUTO_DENY = "method_permission_auto_deny"
    const val METHOD_BACKUP_OFF = "method_backup_off"
    const val METHOD_NEARBY_NOTIFICATIONS = "method_nearby_notifications_off"
    const val METHOD_NEARBY_APPS = "method_nearby_apps_off"

    class Policy(
        val key: String,
        val kind: Int,
        /** Входит ли в пресет «изолированно». В «удобно» не входит ничего. */
        val isolated: Boolean,
    )

    /**
     * Порядок задает порядок на экране. В «изолированно» не входит то, что ломает
     * обычную работу клона: камера, микрофон, звонки, запрет установки и запрет
     * скриншотов -- это ручные переключатели, а не часть пресета.
     */
    val ALL: List<Policy> = listOf(
        Policy("no_cross_profile_copy_paste", KIND_RESTRICTION, isolated = true),
        Policy("no_assist_content", KIND_RESTRICTION, isolated = true),
        Policy("no_content_capture", KIND_RESTRICTION, isolated = true),
        Policy("no_content_suggestions", KIND_RESTRICTION, isolated = true),
        Policy("no_share_location", KIND_RESTRICTION, isolated = true),
        Policy("no_autofill", KIND_RESTRICTION, isolated = true),
        Policy("no_bluetooth_sharing", KIND_RESTRICTION, isolated = true),
        Policy("no_printing", KIND_RESTRICTION, isolated = true),
        Policy("no_modify_accounts", KIND_RESTRICTION, isolated = true),
        Policy("no_config_vpn", KIND_RESTRICTION, isolated = true),
        Policy("no_debugging_features", KIND_RESTRICTION, isolated = true),
        Policy("no_install_unknown_sources", KIND_RESTRICTION, isolated = true),
        Policy(METHOD_BACKUP_OFF, KIND_METHOD, isolated = true),
        Policy(METHOD_PERMISSION_AUTO_DENY, KIND_METHOD, isolated = true),
        Policy(METHOD_NEARBY_NOTIFICATIONS, KIND_METHOD, isolated = true),
        Policy(METHOD_NEARBY_APPS, KIND_METHOD, isolated = true),
        Policy(METHOD_CAMERA, KIND_METHOD, isolated = false),
        Policy(METHOD_SCREEN_CAPTURE, KIND_METHOD, isolated = false),
        Policy("no_unmute_microphone", KIND_RESTRICTION, isolated = false),
        Policy("no_outgoing_calls", KIND_RESTRICTION, isolated = false),
        Policy("no_install_apps", KIND_RESTRICTION, isolated = false),
        Policy("no_uninstall_apps", KIND_RESTRICTION, isolated = false),
        Policy("no_set_wallpaper", KIND_RESTRICTION, isolated = false),
    )

    /** Имя настройки, под которым состояние политики живет в хранилище. */
    fun prefName(key: String): String = "isolation_$key"

    /** Состав пресета: какие политики включены. */
    fun preset(isolated: Boolean): Map<String, Boolean> =
        ALL.associate { it.key to (isolated && it.isolated) }

    /**
     * Какому пресету отвечает текущий набор, или null -- если набор собран руками.
     * Нужно, чтобы экран показывал «изменено вручную», а не врал про пресет.
     */
    fun matchedPreset(current: Map<String, Boolean>): Boolean? = when {
        ALL.all { current[it.key] == false } -> false
        ALL.all { current[it.key] == it.isolated } -> true
        else -> null
    }
}
