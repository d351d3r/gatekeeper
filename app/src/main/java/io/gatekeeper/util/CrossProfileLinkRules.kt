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

    /**
     * Привести произвольный ввод к правилу: отрезать scheme, путь, порт,
     * регистр, финальную точку. ".ru" превращается в "*.ru" (TLD-суффикс).
     * null -- запись бессмысленна и должна быть отброшена.
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        // Отрезаем scheme: "https://example.ru/path" -> "example.ru/path".
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
        // Путь, query, порт: "example.ru/path?q=1", "example.ru:8080".
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        // "*.ru." -> "*.ru"; "example.ru." -> "example.ru".
        if (s.endsWith(".")) s = s.dropLast(1)
        // Ведущая точка означает TLD-суффикс: ".ru" -> "*.ru".
        if (s.startsWith(".")) s = "*" + s
        if (s.isEmpty()) return null
        if (!DOMAIN_REGEX.matches(s)) return null
        // Точки подряд и дефисы по краям лейблов не проходят regex полностью,
        // но "..", "a..b" проходят -- режем явно.
        if (s.replace("*.", "").split('.').any { it.isEmpty() }) return null
        return s
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
