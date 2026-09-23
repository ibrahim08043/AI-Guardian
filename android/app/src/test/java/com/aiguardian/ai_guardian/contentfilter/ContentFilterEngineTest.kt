package com.aiguardian.ai_guardian.contentfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ContentFilterEngine].
 *
 * Tests the deterministic text matching logic:
 * 1. Basic CONTAINS matching (case-insensitive)
 * 2. Basic EXACT matching (case-insensitive)
 * 3. Master switch behavior
 * 4. Self-exclusion (AI Guardian package)
 * 5. Empty/null text handling
 * 6. No rules behavior
 * 7. Disabled rules are skipped
 * 8. First-match-wins precedence
 * 9. Text truncation for long input
 * 10. Special characters in phrases
 * 11. Multiple rules with different modes
 */
class ContentFilterEngineTest {

    private lateinit var engine: ContentFilterEngine

    @Before
    fun setUp() {
        engine = ContentFilterEngine()
    }

    private fun makeRule(
        id: Long = 1,
        phrase: String,
        enabled: Boolean = true,
        matchMode: ContentFilterMatchMode = ContentFilterMatchMode.CONTAINS,
    ): ContentFilterRule {
        return ContentFilterRule(
            id = id,
            phrase = phrase,
            enabled = enabled,
            matchMode = matchMode,
        )
    }

    // -------------------------------------------------------------------------
    // Basic CONTAINS matching
    // -------------------------------------------------------------------------

    @Test
    fun `CONTAINS matches text containing phrase`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("This is spam content", "com.example.app")
        assertTrue(result.matched)
        assertEquals("spam", result.matchedPhrase)
        assertEquals(ContentFilterMatchMode.CONTAINS, result.matchMode)
        assertEquals(1L, result.ruleId)
    }

    @Test
    fun `CONTAINS matching is case-insensitive`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("This is SPAM content", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `CONTAINS matches phrase at start of text`() {
        engine.updateRules(listOf(makeRule(phrase = "hello")))
        val result = engine.evaluateText("hello world", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `CONTAINS matches phrase at end of text`() {
        engine.updateRules(listOf(makeRule(phrase = "world")))
        val result = engine.evaluateText("hello world", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `CONTAINS matches phrase in middle of text`() {
        engine.updateRules(listOf(makeRule(phrase = "is")))
        val result = engine.evaluateText("this is a test", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `CONTAINS does not match unrelated text`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("This is ham content", "com.example.app")
        assertFalse(result.matched)
    }

    // -------------------------------------------------------------------------
    // Basic EXACT matching
    // -------------------------------------------------------------------------

    @Test
    fun `EXACT matches text that exactly equals phrase`() {
        engine.updateRules(listOf(makeRule(phrase = "hello", matchMode = ContentFilterMatchMode.EXACT)))
        val result = engine.evaluateText("hello", "com.example.app")
        assertTrue(result.matched)
        assertEquals("hello", result.matchedPhrase)
        assertEquals(ContentFilterMatchMode.EXACT, result.matchMode)
    }

    @Test
    fun `EXACT does not match text containing phrase`() {
        engine.updateRules(listOf(makeRule(phrase = "hello", matchMode = ContentFilterMatchMode.EXACT)))
        val result = engine.evaluateText("hello world", "com.example.app")
        assertFalse(result.matched)
    }

    @Test
    fun `EXACT matching is case-insensitive`() {
        engine.updateRules(listOf(makeRule(phrase = "hello", matchMode = ContentFilterMatchMode.EXACT)))
        val result = engine.evaluateText("HELLO", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `EXACT matching ignores leading and trailing whitespace in text`() {
        engine.updateRules(listOf(makeRule(phrase = "hello", matchMode = ContentFilterMatchMode.EXACT)))
        val result = engine.evaluateText("  hello  ", "com.example.app")
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Master switch
    // -------------------------------------------------------------------------

    @Test
    fun `master disabled prevents all matching`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        engine.setMasterEnabled(false)
        val result = engine.evaluateText("This is spam content", "com.example.app")
        assertFalse(result.matched)
        assertEquals("master disabled", result.reason)
    }

    @Test
    fun `master re-enabled restores matching`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        engine.setMasterEnabled(false)
        engine.setMasterEnabled(true)
        val result = engine.evaluateText("This is spam content", "com.example.app")
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Self-exclusion
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian package is excluded from filtering`() {
        engine.updateRules(listOf(makeRule(phrase = "ai guardian")))
        val result = engine.evaluateText("ai guardian app", "com.aiguardian.ai_guardian")
        assertFalse(result.matched)
        assertEquals("self-exclusion", result.reason)
    }

    // -------------------------------------------------------------------------
    // Empty/null text handling
    // -------------------------------------------------------------------------

    @Test
    fun `null text returns no match`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText(null, "com.example.app")
        assertFalse(result.matched)
        assertEquals("empty text", result.reason)
    }

    @Test
    fun `blank text returns no match`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("   ", "com.example.app")
        assertFalse(result.matched)
        assertEquals("empty text", result.reason)
    }

    @Test
    fun `empty string text returns no match`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("", "com.example.app")
        assertFalse(result.matched)
    }

    // -------------------------------------------------------------------------
    // No rules
    // -------------------------------------------------------------------------

    @Test
    fun `no rules returns no match`() {
        val result = engine.evaluateText("This is spam content", "com.example.app")
        assertFalse(result.matched)
        assertEquals("no rules", result.reason)
    }

    // -------------------------------------------------------------------------
    // Disabled rules
    // -------------------------------------------------------------------------

    @Test
    fun `disabled rules are skipped`() {
        engine.updateRules(listOf(makeRule(phrase = "spam", enabled = false)))
        val result = engine.evaluateText("This is spam content", "com.example.app")
        assertFalse(result.matched)
    }

    @Test
    fun `only enabled rules are evaluated`() {
        val disabledRule = makeRule(id = 1, phrase = "spam", enabled = false)
        val enabledRule = makeRule(id = 2, phrase = "ham", enabled = true)
        engine.updateRules(listOf(disabledRule, enabledRule))

        val result = engine.evaluateText("This is ham content", "com.example.app")
        assertTrue(result.matched)
        assertEquals(2L, result.ruleId)
        assertEquals("ham", result.matchedPhrase)
    }

    // -------------------------------------------------------------------------
    // First-match-wins
    // -------------------------------------------------------------------------

    @Test
    fun `first matching rule wins`() {
        val rule1 = makeRule(id = 1, phrase = "aaa")
        val rule2 = makeRule(id = 2, phrase = "bbb")
        engine.updateRules(listOf(rule1, rule2))

        val result = engine.evaluateText("aaa bbb", "com.example.app")
        assertTrue(result.matched)
        assertEquals(1L, result.ruleId)
        assertEquals("aaa", result.matchedPhrase)
    }

    // -------------------------------------------------------------------------
    // Text truncation
    // -------------------------------------------------------------------------

    @Test
    fun `long text is truncated for evaluation`() {
        engine.updateRules(listOf(makeRule(phrase = "end")))

        // Text shorter than MAX_TEXT_LENGTH — should match
        val shortText = "a".repeat(5000) + " end"
        val result1 = engine.evaluateText(shortText, "com.example.app")
        assertTrue(result1.matched)
    }

    @Test
    fun `phrase beyond MAX_TEXT_LENGTH is not found`() {
        engine.updateRules(listOf(makeRule(phrase = "end")))

        // Text longer than MAX_TEXT_LENGTH — "end" is beyond the cutoff
        val longText = "a".repeat(ContentFilterEngine.MAX_TEXT_LENGTH + 100)
        val result = engine.evaluateText(longText, "com.example.app")
        assertFalse(result.matched)
    }

    // -------------------------------------------------------------------------
    // Special characters
    // -------------------------------------------------------------------------

    @Test
    fun `phrase with special characters matches`() {
        engine.updateRules(listOf(makeRule(phrase = "foo@bar.com")))
        val result = engine.evaluateText("email is foo@bar.com today", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `unicode phrase matches`() {
        engine.updateRules(listOf(makeRule(phrase = "café")))
        val result = engine.evaluateText("I love café au lait", "com.example.app")
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Multiple rules with different modes
    // -------------------------------------------------------------------------

    @Test
    fun `mix of CONTAINS and EXACT rules works`() {
        val containsRule = makeRule(id = 1, phrase = "spam", matchMode = ContentFilterMatchMode.CONTAINS)
        val exactRule = makeRule(id = 2, phrase = "ham", matchMode = ContentFilterMatchMode.EXACT)
        engine.updateRules(listOf(containsRule, exactRule))

        // "spam" should match CONTAINS rule
        val result1 = engine.evaluateText("This is spam", "com.example.app")
        assertTrue(result1.matched)
        assertEquals(1L, result1.ruleId)

        // "ham" alone should match EXACT rule
        val result2 = engine.evaluateText("ham", "com.example.app")
        assertTrue(result2.matched)
        assertEquals(2L, result2.ruleId)

        // "ham sandwich" should NOT match EXACT rule but would match CONTAINS if phrase was "ham"
        val result3 = engine.evaluateText("ham sandwich", "com.example.app")
        assertFalse(result3.matched)
    }

    // -------------------------------------------------------------------------
    // Null package
    // -------------------------------------------------------------------------

    @Test
    fun `null package does not cause crash`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))
        val result = engine.evaluateText("This is spam", null)
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Rules update
    // -------------------------------------------------------------------------

    @Test
    fun `updating rules replaces previous rules`() {
        engine.updateRules(listOf(makeRule(id = 1, phrase = "spam")))
        val result1 = engine.evaluateText("spam", "com.example.app")
        assertTrue(result1.matched)

        engine.updateRules(listOf(makeRule(id = 2, phrase = "ham")))
        val result2 = engine.evaluateText("spam", "com.example.app")
        assertFalse(result2.matched)

        val result3 = engine.evaluateText("ham", "com.example.app")
        assertTrue(result3.matched)
    }

    // -------------------------------------------------------------------------
    // ContentFilterRule validation
    // -------------------------------------------------------------------------

    @Test
    fun `normalizePhrase trims and lowercases`() {
        val result = ContentFilterRule.normalizePhrase("  Spam  ")
        assertEquals("spam", result)
    }

    @Test
    fun `normalizePhrase returns null for blank input`() {
        assertEquals(null, ContentFilterRule.normalizePhrase(""))
        assertEquals(null, ContentFilterRule.normalizePhrase("   "))
        assertEquals(null, ContentFilterRule.normalizePhrase(null))
    }

    @Test
    fun `validatePhrase accepts valid phrase`() {
        val error = ContentFilterRule.validatePhrase("spam")
        assertEquals(null, error)
    }

    @Test
    fun `validatePhrase rejects empty phrase`() {
        val error = ContentFilterRule.validatePhrase("")
        assertEquals("Phrase cannot be empty", error)
    }

    @Test
    fun `validatePhrase rejects too-long phrase`() {
        val longPhrase = "a".repeat(ContentFilterRule.MAX_PHRASE_LENGTH + 1)
        val error = ContentFilterRule.validatePhrase(longPhrase)
        assertTrue(error?.contains("200 characters") == true)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty phrase rule is skipped`() {
        engine.updateRules(listOf(makeRule(id = 1, phrase = "")))
        val result = engine.evaluateText("anything", "com.example.app")
        assertFalse(result.matched)
    }

    @Test
    fun `very short phrase matches`() {
        engine.updateRules(listOf(makeRule(phrase = "a")))
        val result = engine.evaluateText("a", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `phrase with spaces matches`() {
        engine.updateRules(listOf(makeRule(phrase = "hello world")))
        val result = engine.evaluateText("say hello world now", "com.example.app")
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Performance: 80+ rules
    // -------------------------------------------------------------------------

    @Test
    fun `80 CONTAINS rules - matching rule found`() {
        // Use unique phrases that don't overlap as substrings
        val rules = (1..80).map { makeRule(id = it.toLong(), phrase = "block_${it}_xyz") }
        engine.updateRules(rules)

        // Match one of the rules — "block_50_xyz" won't match "block_5_xyz" because
        // "block_5_xyz" is not a substring of "block_50_xyz"
        val result = engine.evaluateText("this contains block_50_xyz in it", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `80 CONTAINS rules - no match on unrelated text`() {
        val rules = (1..80).map { makeRule(id = it.toLong(), phrase = "blocked_word_$it") }
        engine.updateRules(rules)

        val result = engine.evaluateText("this is completely innocent text", "com.example.app")
        assertFalse(result.matched)
    }

    @Test
    fun `80 mixed EXACT and CONTAINS rules`() {
        val exactRules = (1..40).map { makeRule(id = it.toLong(), phrase = "exact_word_$it", matchMode = ContentFilterMatchMode.EXACT) }
        val containsRules = (41..80).map { makeRule(id = it.toLong(), phrase = "contains_word_$it") }
        engine.updateRules(exactRules + containsRules)

        // Match an EXACT rule
        val result1 = engine.evaluateText("exact_word_20", "com.example.app")
        assertTrue(result1.matched)
        assertEquals(20L, result1.ruleId)

        // Match a CONTAINS rule
        val result2 = engine.evaluateText("this has contains_word_60 inside", "com.example.app")
        assertTrue(result2.matched)
        assertEquals(60L, result2.ruleId)

        // No match
        val result3 = engine.evaluateText("nothing here matches", "com.example.app")
        assertFalse(result3.matched)
    }

    @Test
    fun `100 CONTAINS rules - performance check`() {
        val rules = (1..100).map { makeRule(id = it.toLong(), phrase = "blocked_word_$it") }
        engine.updateRules(rules)

        val start = System.currentTimeMillis()
        // Run 100 evaluations
        repeat(100) {
            engine.evaluateText("this is test text with blocked_word_50 inside", "com.example.app")
        }
        val elapsed = System.currentTimeMillis() - start

        // 100 evaluations of 100 rules should complete well under 1 second
        assertTrue("100 evaluations took ${elapsed}ms, expected < 1000ms", elapsed < 1000)
    }

    @Test
    fun `repeated identical text - consistent results`() {
        engine.updateRules(listOf(makeRule(phrase = "spam")))

        repeat(50) {
            val result = engine.evaluateText("this is spam content", "com.example.app")
            assertTrue("Iteration $it: expected match", result.matched)
            assertEquals("spam", result.matchedPhrase)
        }
    }

    @Test
    fun `unicode text matching`() {
        engine.updateRules(listOf(makeRule(phrase = "日本語")))
        val result = engine.evaluateText("this contains 日本語 text", "com.example.app")
        assertTrue(result.matched)
    }

    @Test
    fun `emoji in text does not break matching`() {
        engine.updateRules(listOf(makeRule(phrase = "hello")))
        val result = engine.evaluateText("👋 hello 🌍 world", "com.example.app")
        assertTrue(result.matched)
    }
}
