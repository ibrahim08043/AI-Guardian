package com.aiguardian.ai_guardian.contentfilter

import android.util.Log

/**
 * Local content filter engine with deterministic text matching.
 *
 * Evaluates a text string against a set of [ContentFilterRule]s and returns
 * whether any rule matches. This engine:
 * - Is deterministic (same input → same output)
 * - Uses fail-safe defaults (empty rules → no match)
 * - Normalizes text for case-insensitive comparison
 * - Applies text length bounds for performance protection
 * - Never crashes on malformed input
 *
 * The engine does NOT read from the database — it receives a list of rules
 * via [updateRules]. The caller (AccessibilityService) is responsible for
 * loading rules from the repository and calling [updateRules] when they change.
 *
 * ## Matching Logic
 *
 * For each enabled rule:
 * 1. Normalize the input text (trim, lowercase)
 * 2. If matchMode is CONTAINS: check if text contains the phrase
 * 3. If matchMode is EXACT: check if text equals the phrase
 * 4. First match wins — return immediately
 *
 * ## Performance Constraints
 *
 * - Maximum text length: [MAX_TEXT_LENGTH] characters (longer text is truncated)
 * - Rules are held in memory (typically < 100 rules, negligible cost)
 * - No regex, no network, no allocation-heavy operations
 */
class ContentFilterEngine {

    companion object {
        private const val TAG = "AIGuardianFilter"

        /**
         * Maximum text length to evaluate.
         * Accessibility events can expose very long text blocks (entire screen content).
         * Truncating protects against CPU spikes from large text matching.
         */
        const val MAX_TEXT_LENGTH = 10000

        /** AI Guardian's own package — never filter content from our own app. */
        private const val OWN_PACKAGE = "com.aiguardian.ai_guardian"
    }

    /** Current set of enabled rules. Updated via [updateRules]. */
    @Volatile
    private var enabledRules: List<ContentFilterRule> = emptyList()

    /** Pre-normalized EXACT phrases for O(1) HashSet lookup. */
    @Volatile
    private var exactPhrases: Set<String> = emptySet()

    /** Pre-normalized CONTAINS phrases for scanning. */
    @Volatile
    private var containsPhrases: List<String> = emptyList()

    /** Master switch — when false, all matching is bypassed. */
    @Volatile
    private var masterEnabled: Boolean = true

    /**
     * Update the set of enabled rules.
     * Pre-normalizes phrases into exact/contains buckets for fast matching.
     * Called by the AccessibilityService when rules change in the database.
     */
    fun updateRules(rules: List<ContentFilterRule>) {
        enabledRules = rules.filter { it.enabled }
        // Pre-normalize into lookup structures
        val exact = mutableSetOf<String>()
        val contains = mutableListOf<String>()
        for (rule in enabledRules) {
            val phrase = rule.phrase
            if (phrase.isEmpty()) continue
            when (rule.matchMode) {
                ContentFilterMatchMode.EXACT -> exact.add(phrase)
                ContentFilterMatchMode.CONTAINS -> contains.add(phrase)
            }
        }
        exactPhrases = exact
        containsPhrases = contains
        Log.d(TAG, "Updated rules: ${enabledRules.size} enabled (${exact.size} exact, ${contains.size} contains)")
    }

    /**
     * Enable or disable the master switch.
     * When disabled, [evaluateText] always returns ALLOW regardless of rules.
     */
    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled = enabled
        Log.d(TAG, "Master switch: $enabled")
    }

    /**
     * Check if the master switch is enabled.
     */
    fun isMasterEnabled(): Boolean = masterEnabled

    /**
     * Get the count of currently enabled rules.
     */
    fun getEnabledRuleCount(): Int = enabledRules.size

    /**
     * Evaluate text content against all enabled rules.
     *
     * @param text The text to check (from AccessibilityEvent text)
     * @param packageName The package that produced the text (for self-exclusion)
     * @return A [ContentFilterResult] indicating whether a rule matched
     */
    fun evaluateText(text: CharSequence?, packageName: String?): ContentFilterResult {
        // Fail-safe: null/blank text → no match
        if (text.isNullOrBlank()) {
            return ContentFilterResult.noMatch("empty text")
        }

        // Self-exclusion: AI Guardian's own text is never filtered
        if (packageName == OWN_PACKAGE) {
            return ContentFilterResult.noMatch("self-exclusion")
        }

        // Master switch off → no matching
        if (!masterEnabled) {
            return ContentFilterResult.noMatch("master disabled")
        }

        // No rules → no match
        if (enabledRules.isEmpty()) {
            return ContentFilterResult.noMatch("no rules")
        }

        // Truncate long text for performance
        val rawText = text.toString()
        val textToCheck = if (rawText.length > MAX_TEXT_LENGTH) {
            rawText.substring(0, MAX_TEXT_LENGTH)
        } else {
            rawText
        }

        // Normalize text once for comparison
        val normalizedText = textToCheck.trim().lowercase()

        // 1. Fast O(1) exact match via HashSet
        if (normalizedText in exactPhrases) {
            val rule = enabledRules.first { it.matchMode == ContentFilterMatchMode.EXACT && it.phrase == normalizedText }
            Log.i(TAG, "Rule matched: '${rule.phrase}' (EXACT) in text from $packageName")
            return ContentFilterResult(
                matched = true,
                matchedPhrase = rule.phrase,
                matchMode = rule.matchMode,
                ruleId = rule.id,
                packageName = packageName.orEmpty(),
                reason = "rule matched",
            )
        }

        // 2. Scan CONTAINS phrases
        for (phrase in containsPhrases) {
            if (normalizedText.contains(phrase)) {
                val rule = enabledRules.first { it.matchMode == ContentFilterMatchMode.CONTAINS && it.phrase == phrase }
                Log.i(TAG, "Rule matched: '${rule.phrase}' (CONTAINS) in text from $packageName")
                return ContentFilterResult(
                    matched = true,
                    matchedPhrase = rule.phrase,
                    matchMode = rule.matchMode,
                    ruleId = rule.id,
                    packageName = packageName.orEmpty(),
                    reason = "rule matched",
                )
            }
        }

        return ContentFilterResult.noMatch("no rule matched")
    }
}

/**
 * Result of a content filter evaluation.
 *
 * @property matched Whether any rule matched the input text
 * @property matchedPhrase The phrase that matched (empty if no match)
 * @property matchMode The match mode used (CONTAINS or EXACT)
 * @property ruleId The ID of the matching rule (0 if no match)
 * @property packageName The package that produced the text
 * @property reason Human-readable reason for the result
 */
data class ContentFilterResult(
    val matched: Boolean,
    val matchedPhrase: String,
    val matchMode: ContentFilterMatchMode,
    val ruleId: Long,
    val packageName: String,
    val reason: String,
) {
    companion object {
        fun noMatch(reason: String): ContentFilterResult {
            return ContentFilterResult(
                matched = false,
                matchedPhrase = "",
                matchMode = ContentFilterMatchMode.CONTAINS,
                ruleId = 0,
                packageName = "",
                reason = reason,
            )
        }
    }
}
