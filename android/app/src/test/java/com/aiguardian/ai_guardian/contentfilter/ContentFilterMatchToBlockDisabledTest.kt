package com.aiguardian.ai_guardian.contentfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused tests for the TEMPORARILY DISABLED content-filter match→block path.
 *
 * Context:
 * - UI for Content Filtering remains hidden.
 * - ContentFilterEngine keyword matching is intentionally still active in code.
 * - AIGuardianAccessibilityService.handleTextEvent no longer launches
 *   BlockActivity on a content-filter match (enforcement conversion commented out).
 * - Website/domain and app-policy enforcement paths are unchanged.
 *
 * These tests verify that keyword matching itself still exists and works,
 * so the match→block condition can be restored later without rewriting matching.
 */
class ContentFilterMatchToBlockDisabledTest {

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

    @Test
    fun `content filter keyword matching still exists and detects a match`() {
        // Matching engine remains intact even though match→block is disabled.
        engine.updateRules(listOf(makeRule(phrase = "gambling")))
        val result = engine.evaluateText("visit gambling site now", "com.example.browser")

        assertTrue("Keyword matching code must remain functional", result.matched)
        assertEquals("gambling", result.matchedPhrase)
        assertEquals(ContentFilterMatchMode.CONTAINS, result.matchMode)
        assertEquals(1L, result.ruleId)
    }

    @Test
    fun `exact content filter matching still exists`() {
        engine.updateRules(
            listOf(makeRule(phrase = "explicit content", matchMode = ContentFilterMatchMode.EXACT))
        )
        val result = engine.evaluateText("explicit content", "com.example.app")

        assertTrue(result.matched)
        assertEquals("explicit content", result.matchedPhrase)
        assertEquals(ContentFilterMatchMode.EXACT, result.matchMode)
    }

    @Test
    fun `master switch and rule loading APIs still exist for restore path`() {
        // APIs used by the original enforcement path must remain available.
        engine.setMasterEnabled(true)
        assertTrue(engine.isMasterEnabled())

        engine.updateRules(listOf(makeRule(phrase = "keyword")))
        assertEquals(1, engine.getEnabledRuleCount())

        engine.setMasterEnabled(false)
        val bypassed = engine.evaluateText("keyword", "com.example.app")
        assertTrue(!bypassed.matched)
        assertEquals("master disabled", bypassed.reason)
    }

    @Test
    fun `non-matching text still returns noMatch`() {
        engine.updateRules(listOf(makeRule(phrase = "blockedword")))
        val result = engine.evaluateText("completely harmless text", "com.example.app")

        assertTrue(!result.matched)
        assertEquals("no rule matched", result.reason)
    }
}
