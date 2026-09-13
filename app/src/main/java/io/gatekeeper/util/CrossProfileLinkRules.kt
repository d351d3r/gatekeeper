package io.gatekeeper.util

/**
 * Правила перенаправления ссылок в рабочий профиль (C2, 4PDA #2060, #1624).
 * Чистая логика: нормализация пользовательского ввода и построение
 * host-паттернов IntentFilter. Саму сборку фильтра и DPM-вызовы делает
 * GatekeeperService -- здесь только то, что можно гонять на JVM.
 *
 * Формат правила: домен нижнего регистра, опционально с ведущим `*.`.
 * Каждое правило покрывает домен и все его поддомены.
 */
object CrossProfileLinkRules {
    private val DOMAIN_REGEX = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")
    private const val SCHEME_DELIMITER = "://"

    /**
     * Привести произвольный ввод к правилу: отрезать scheme, путь, порт,
     * регистр, финальную точку. ".ru" превращается в "*.ru" (TLD-суффикс).
     * null -- запись бессмысленна и должна быть отброшена.
     */
    fun normalize(input: String): String? {
        var s = stripToHost(input.trim().lowercase()) ?: return null
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.startsWith(".")) s = "*" + s
        val labels = s.replace("*.", "").split('.')
        val valid = s.isNotEmpty() && DOMAIN_REGEX.matches(s) && labels.none { it.isEmpty() }
        return if (valid) s else null
    }

    /** "https://example.ru/path?q=1:8080" -> "example.ru"; "" -> null. */
    private fun stripToHost(raw: String): String? {
        val withoutScheme = raw.substringAfter(SCHEME_DELIMITER)
        val host = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
        return host.ifEmpty { null }
    }

    /**
     * Разобрать строку правил (запятые/пробелы/переносы строк) в список
     * нормализованных, без дублей, в порядке первого появления.
     */
    fun parseRules(input: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in input.split(',', ';', ' ', '\n', '\t')) {
            val rule = normalize(raw) ?: continue
            out.add(rule)
        }
        return out.toList()
    }

    /**
     * Authority-паттерны IntentFilter для правила: "example.ru" покрывает и
     * apex, и поддомены; "*.ru" уже суффикс и покрывает сам себя.
     */
    fun hostPatterns(rule: String): List<String> =
        if (rule.startsWith("*.")) listOf(rule) else listOf(rule, "*.$rule")
}
