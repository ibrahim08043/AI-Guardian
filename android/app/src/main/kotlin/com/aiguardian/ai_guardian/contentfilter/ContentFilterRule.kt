package com.aiguardian.ai_guardian.contentfilter

/**
 * A single content filter rule.
 *
 * Represents a blocked word or phrase that the ContentFilterEngine
 * matches against text exposed through the AccessibilityService.
 *
 * Properties:
 * - [id]: Database primary key (auto-generated)
 * - [phrase]: The word or phrase to match (non-blank)
 * - [enabled]: Whether this rule is currently active
 * - [matchMode]: CONTAINS (text contains phrase) or EXACT (exact match)
 * - [createdAt]: Epoch millis when created
 * - [updatedAt]: Epoch millis when last updated
 *
 * Constraints:
 * - [phrase] is trimmed and stored in lowercase for case-insensitive matching
 * - [id] is 0 for unsaved rules (pending insert)
 *
 * Thread-safety: Data class is immutable — safe to share across threads.
 */
data class ContentFilterRule(
    val id: Long = 0,
    val phrase: String,
    val enabled: Boolean = true,
    val matchMode: ContentFilterMatchMode = ContentFilterMatchMode.CONTAINS,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Maximum allowed phrase length in characters.
         * Protects against accidental insertion of large text blocks.
         */
        const val MAX_PHRASE_LENGTH = 200

        /**
         * Normalize a phrase for storage: trim whitespace, lowercase.
         * Returns null if the result is blank after normalization.
         */
        fun normalizePhrase(input: String?): String? {
            if (input == null) return null
            val normalized = input.trim().lowercase()
            return normalized.ifBlank { null }
        }

        /**
         * Validate a phrase for creation.
         * Returns null if valid, or an error message if invalid.
         */
        fun validatePhrase(phrase: String): String? {
            val normalized = normalizePhrase(phrase) ?: return "Phrase cannot be empty"
            if (normalized.length > MAX_PHRASE_LENGTH) {
                return "Phrase must be $MAX_PHRASE_LENGTH characters or fewer"
            }
            return null
        }
    }
}
