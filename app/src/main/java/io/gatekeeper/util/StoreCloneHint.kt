package io.gatekeeper.util

/**
 * Одноразовая подсказка после настройки: "магазина приложений нет в рабочем
 * профиле -- клонировать?". Частый вопрос новичков (4PDA #2097-#2118, #2100):
 * профиль создан, а ставить туда приложения нечем. Чистая логика решений,
 * чтобы MainActivity оставался тонким, а пороги были покрыты unit-тестами.
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

    /** Показывать ли диалог при старте главного экрана. */
    fun isDue(state: Int, snoozedAt: Long, now: Long): Boolean = when (state) {
        STATE_NEVER -> false
        STATE_NEVER_ASKED -> true
        else -> now - snoozedAt >= SNOOZE_MS
    }

    /**
     * Кого клонировать: первый магазин, установленный в личном профиле
     * ([mainHasApk] == true -- есть непустой sourceDir) и отсутствующий
     * в рабочем ([workPackages]). Null -- спрашивать не о чем.
     */
    fun pickCandidate(mainHasApk: Map<String, Boolean>, workPackages: Set<String>): String? {
        for (pkg in CANDIDATES) {
            if (workPackages.contains(pkg)) continue
            if (mainHasApk[pkg] == true) return pkg
        }
        return null
    }
}
