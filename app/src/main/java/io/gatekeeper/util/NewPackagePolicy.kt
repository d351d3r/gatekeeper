package io.gatekeeper.util

/**
 * Что считать новым приложением профиля, которое стоит добавить в список
 * автозаморозки (F3, воскрешение E-6). Чистая логика без Android: решение
 * разбирается по шагам и проверяется юнит-тестами, а не только на стенде.
 */
object NewPackagePolicy {

    /** Признаки пакета, собранные на стороне рабочего профиля. */
    data class Candidate(
        val packageName: String,
        val installed: Boolean,
        val system: Boolean,
        val launchable: Boolean,
    )

    /**
     * Первый запуск базлайна не даёт: номер последовательности ещё не сохранён,
     * и `getChangedPackages` вернул бы всё, что менялось с загрузки. Молча
     * записать в автозаморозку пачку чужих приложений хуже, чем пропустить их:
     * список решает, что заморозится при блокировке экрана.
     */
    fun isBaselineRun(storedSequence: Int?): Boolean = storedSequence == null

    /**
     * Отбор: своё приложение, системные и то, что нельзя запустить, не считаются
     * установкой пользователя. Уже известное списку не повторяем -- проверку
     * отказа (opt-out) делает личная сторона, у неё этот список и живёт.
     *
     * [known] -- пакеты, которые профиль уже видел. Без этого множества обновление
     * приложения выглядело бы как установка: номер последовательности двигают и
     * обновления, и удаления, и приложение, которое пользователь намеренно держал
     * вне списка, вернулось бы в него после первого же апдейта.
     */
    fun pickNew(
        candidates: List<Candidate>,
        ownPackage: String,
        alreadyListed: Set<String>,
        known: Set<String>,
    ): List<String> = eligible(candidates, ownPackage)
        .filterNot { it in alreadyListed }
        .filterNot { it in known }
        .distinct()

    /** Пакеты профиля, которые вообще считаются приложениями пользователя. */
    fun eligible(candidates: List<Candidate>, ownPackage: String): List<String> = candidates
        .filter { it.packageName != ownPackage }
        .filter { it.installed && !it.system && it.launchable }
        .map { it.packageName }
        .distinct()
}
