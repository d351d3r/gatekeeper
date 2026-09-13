package io.gatekeeper.util

/**
 * Выбор кросс-профильного реле среди кандидатов резолва (D4a). Без Android SDK.
 *
 * Явную адресацию тут применить нельзя: доставку в другой профиль выполняет системный
 * `IntentForwarderActivity`, и его псевдоним зависит от направления
 * (`ForwardIntentToManagedProfile` / `ForwardIntentToParent`), то есть имя класса приходится
 * брать из резолва. Проверить можно пакет: форвардер живет в `android`, и любой другой
 * кандидат -- это стороннее приложение, объявившее наши строки действий. Отдавать ему
 * подписанный интент нельзя.
 */
object ProfileForwarder {
    const val SYSTEM_PACKAGE = "android"

    data class Candidate(val packageName: String, val className: String)

    /**
     * Первый системный форвардер или null. Направление задает тот, кто регистрировал
     * кросс-профильный фильтр, поэтому при нескольких системных кандидатах берется первый --
     * как и до ужесточения. Отдельная проверка «не мы сами» не нужна: наш пакет
     * системным не бывает.
     */
    fun pickForwarder(candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { it.packageName == SYSTEM_PACKAGE }
}
