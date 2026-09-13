package io.gatekeeper.util

/**
 * Полезна ли автозаморозка по подъему VPN на этом устройстве.
 *
 * Замер 10.08.2026 (`docs/threat_model.md`): рабочий профиль -- отдельный пользователь со
 * своей маршрутизацией, и VPN, поднятый в личном профиле, трафик профиля не покрывает.
 * В такой конфигурации замораживать приложения при подъеме VPN незачем: скрывать нечего,
 * а уведомления гасятся. Проверять это надо по факту, а не по теории, и не по наличию
 * `tun0`: интерфейс виден из любого профиля.
 */
enum class VpnRoutingAdvice {
    /** Туннель не поднят -- сказать пока нечего. */
    VPN_DOWN,

    /** Рабочий профиль не отвечает: проверить нечем. */
    WORK_PROFILE_UNREACHABLE,

    /** Туннель есть, трафик профиля идет мимо него -- переключатель бесполезен. */
    WORK_PROFILE_BYPASSES_TUNNEL,

    /** Трафик профиля идет через туннель -- заморозка имеет смысл. */
    WORK_PROFILE_IN_TUNNEL;

    companion object {
        fun evaluate(personalTunneled: Boolean, workTunneled: Boolean?): VpnRoutingAdvice = when {
            workTunneled == true -> WORK_PROFILE_IN_TUNNEL
            !personalTunneled -> VPN_DOWN
            workTunneled == null -> WORK_PROFILE_UNREACHABLE
            else -> WORK_PROFILE_BYPASSES_TUNNEL
        }
    }
}
