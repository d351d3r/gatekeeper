package io.gatekeeper.util

/** Что морозит сторож по срабатыванию триггера. */
enum class AntiSpyFreezeScope(val stored: Int) {
    /** Тот же список, что у кнопки-снежинки. */
    AUTO_FREEZE_LIST(0),

    /** Все сторонние приложения рабочего профиля. */
    WHOLE_WORK_PROFILE(1);

    companion object {
        fun fromStored(value: Int): AntiSpyFreezeScope =
            entries.firstOrNull { it.stored == value } ?: AUTO_FREEZE_LIST
    }
}

/** Реакция сторожа на подъём VPN-туннеля. */
enum class AntiSpyReaction { IGNORE, NOTIFY_ONLY, FREEZE_AFTER_DELAY, FREEZE_NOW }

/**
 * Настройки сторожа VPN. Заморозка гасит уведомления мессенджера и банков, поэтому решение
 * принимается только по явному выбору пользователя: главный переключатель выключен по
 * умолчанию, триггер один -- подъём туннеля. Заморозка по блокировке экрана переехала
 * на экран «Заморозка» (единый владелец триггера, редизайн шаг 5).
 */
data class AntiSpyWatchConfig(
    val enabled: Boolean = false,
    val freezeOnVpn: Boolean = DEFAULT_FREEZE_ON_VPN,
    val scope: AntiSpyFreezeScope = AntiSpyFreezeScope.AUTO_FREEZE_LIST,
    val notifyOnly: Boolean = false,
    val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
) {
    /** Держать процесс сторожа имеет смысл только при включённом триггере. */
    val watcherNeeded: Boolean get() = enabled && freezeOnVpn

    /** Решение по факту подъёма туннеля; выводится целиком из настроек. */
    val reaction: AntiSpyReaction get() = when {
        !enabled || !freezeOnVpn -> AntiSpyReaction.IGNORE
        notifyOnly -> AntiSpyReaction.NOTIFY_ONLY
        delaySeconds > 0 -> AntiSpyReaction.FREEZE_AFTER_DELAY
        else -> AntiSpyReaction.FREEZE_NOW
    }

    companion object {
        const val DEFAULT_FREEZE_ON_VPN = true
        const val DEFAULT_DELAY_SECONDS = 15
        val DELAY_CHOICES_SECONDS = intArrayOf(0, 5, 15, 30, 60)
    }
}

/**
 * Откат перезапуска сторожа. Прежний код будил себя будильником через 1500 мс после каждой
 * смерти, включая смерть по неустранимой причине, и крутился в цикле десятками итераций.
 */
object AntiSpyWatchRestart {
    const val MAX_ATTEMPTS = 4
    private const val FIRST_DELAY_MS = 5_000L

    /** null -- попытки исчерпаны, перезапускать больше не надо. */
    fun delayMsForAttempt(attempt: Int): Long? {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) {
            return null
        }
        return FIRST_DELAY_MS shl attempt
    }
}
