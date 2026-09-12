package io.gatekeeper.util

/**
 * Одноразовая подсказка после настройки: "магазина приложений нет в рабочем
 * профиле -- клонировать?". Частый вопрос новичков (4PDA #2097-#2118, #2100):
 * профиль создан, а ставить туда приложения нечем. Чистая логика решений,
 * чтобы MainActivity оставался тонким, а пороги были покрыты unit-тестами.
 *
 * Доступность магазина в рабочем профиле проверяется по фактическому
 * состоянию (FLAG_INSTALLED для этого пользователя + launcher-активити),
 * а не по вхождению в выдачу getApps(showAll = true): она включает
 * системные пакеты из образа прошивки с неснятым FLAG_INSTALLED, из-за чего
 * Play Store "присутствовал" в любом списке GMS-устройства и подсказка не
 * срабатывала никогда (C1, 12.09.2026).
 */
object StoreCloneHint {
    const val STATE_NEVER_ASKED = 0
    const val STATE_SNOOZED = 1
    const val STATE_NEVER = 2

    /** "Позже" молчит неделю, потом спрашивает снова. */
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    const val PLAY_STORE = "com.android.vending"
    const val RU_STORE = "ru.vk.store"

    /** Приоритет совпадает с ожиданиями аудитории: сначала Play, иначе RuStore. */
    val CANDIDATES = listOf(PLAY_STORE, RU_STORE)

    /** Магазин установлен в рабочем профиле и запускается -- спрашивать не о чем. */
    const val WORK_STATE_AVAILABLE = 0

    /** Установлен, но скрыт (заморожен) -- правильное предложение: разморозить. */
    const val WORK_STATE_FROZEN = 1

    /** Нет в профиле (или лежит в образе невключённым) -- предложить клонирование. */
    const val WORK_STATE_ABSENT = 2

    const val ACTION_CLONE = 0
    const val ACTION_UNFREEZE = 1

    /** Показывать ли диалог при старте главного экрана. */
    fun isDue(state: Int, snoozedAt: Long, now: Long): Boolean = when (state) {
        STATE_NEVER -> false
        STATE_NEVER_ASKED -> true
        else -> now - snoozedAt >= SNOOZE_MS
    }

    /**
     * Состояние магазина в рабочем профиле по факту, а не по списку:
     * снятый FLAG_INSTALLED у системного пакета из образа -- это отсутствие.
     * Скрытый считаем замороженным независимо от launcher-запроса: у скрытого
     * пакета PM launcher-интент может не вернуть, а разморозка всё равно
     * правильная операция.
     */
    fun classifyWorkState(installed: Boolean, hidden: Boolean, canLaunch: Boolean): Int = when {
        !installed -> WORK_STATE_ABSENT
        hidden -> WORK_STATE_FROZEN
        canLaunch -> WORK_STATE_AVAILABLE
        else -> WORK_STATE_ABSENT
    }

    /**
     * Кого и что предложить: первый по приоритету магазин, у которого есть
     * непустой sourceDir в личном профиле ([mainHasApk] == true) и который в
     * рабочем либо отсутствует (клонировать), либо заморожен (разморозить).
     * Магазин, установленный и запускаемый в рабочем, пропускаем.
     *
     * @return пара (пакет, ACTION_CLONE/ACTION_UNFREEZE) или null -- спрашивать не о чем.
     */
    fun pickAction(mainHasApk: Map<String, Boolean>, workStates: Map<String, Int>): Pair<String, Int>? =
        CANDIDATES.firstNotNullOfOrNull { pkg ->
            when (workStates[pkg]) {
                WORK_STATE_AVAILABLE -> null
                WORK_STATE_FROZEN -> pkg to ACTION_UNFREEZE
                else -> if (mainHasApk[pkg] == true) pkg to ACTION_CLONE else null
            }
        }
}
