package com.aiguardian.ai_guardian.contentfilter

/**
 * Match modes for content filter rules.
 *
 * CONTAINS: The text contains the phrase anywhere (case-insensitive).
 * EXACT: The normalized text matches the phrase exactly (case-insensitive).
 */
enum class ContentFilterMatchMode {
    CONTAINS,
    EXACT;

    companion object {
        /**
         * Parse from string, defaulting to CONTAINS for unknown values.
         */
        fun fromString(value: String?): ContentFilterMatchMode {
            return try {
                valueOf(value ?: CONTAINS.name)
            } catch (e: IllegalArgumentException) {
                CONTAINS
            }
        }
    }
}
