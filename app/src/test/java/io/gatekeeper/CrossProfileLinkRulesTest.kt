package io.gatekeeper

import io.gatekeeper.util.CrossProfileLinkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossProfileLinkRulesTest {
    @Test
    fun stripsSchemePathQueryAndPort() {
        assertEquals("example.ru", CrossProfileLinkRules.normalize("https://example.ru/path?q=1"))
        assertEquals("example.ru", CrossProfileLinkRules.normalize("http://example.ru:8080/a"))
        assertEquals("example.ru", CrossProfileLinkRules.normalize("example.ru/path"))
    }

    @Test
    fun lowercasesAndTrims() {
        assertEquals("example.ru", CrossProfileLinkRules.normalize("  HTTPS://Example.RU.  "))
    }

    @Test
    fun leadingDotMeansTldSuffix() {
        assertEquals("*.ru", CrossProfileLinkRules.normalize(".ru"))
        assertEquals("*.su", CrossProfileLinkRules.normalize(" *.su "))
    }

    @Test
    fun explicitWildcardSurvives() {
        assertEquals("*.sub.example.com", CrossProfileLinkRules.normalize("*.sub.example.com"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(CrossProfileLinkRules.normalize(""))
        assertNull(CrossProfileLinkRules.normalize("   "))
        assertNull(CrossProfileLinkRules.normalize("not a domain"))
        assertNull(CrossProfileLinkRules.normalize("exa mple.ru"))
        assertNull(CrossProfileLinkRules.normalize("..ru"))
        assertNull(CrossProfileLinkRules.normalize("-example.ru"))
        assertNull(CrossProfileLinkRules.normalize("https://"))
    }

    @Test
    fun parseRulesSplitsSeparatesAndDedupes() {
        val rules = CrossProfileLinkRules.parseRules(".ru, example.com\nEXAMPLE.com ; *.su nope!")
        assertEquals(listOf("*.ru", "example.com", "*.su"), rules)
    }

    @Test
    fun parseRulesDropsInvalidEntries() {
        assertEquals(listOf("example.ru"), CrossProfileLinkRules.parseRules("example.ru, , nope!"))
    }

    @Test
    fun hostPatternsCoverApexAndSubdomains() {
        assertEquals(
            listOf("example.ru", "*.example.ru"),
            CrossProfileLinkRules.hostPatterns("example.ru"),
        )
    }

    @Test
    fun hostPatternsKeepSuffixAsIs() {
        assertEquals(listOf("*.ru"), CrossProfileLinkRules.hostPatterns("*.ru"))
    }
}
