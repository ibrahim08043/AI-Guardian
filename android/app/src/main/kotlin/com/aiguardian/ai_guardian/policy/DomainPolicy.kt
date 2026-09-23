package com.aiguardian.ai_guardian.policy

/**
 * Represents a domain blocking rule.
 *
 * Each rule targets a single normalized domain (e.g., "youtube.com").
 * Domains are normalized: lowercase, no protocol, no path, no trailing dot.
 */
data class DomainPolicy(
    val domain: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Pre-compiled regex for stripping http:// or https:// protocol prefix. */
        private val REGEX_PROTOCOL = Regex("^https?://", RegexOption.IGNORE_CASE)

        /** Pre-compiled regex for validating individual DNS labels (RFC-compliant). */
        private val REGEX_LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

        /**
         * Normalize a domain input:
         * - Lowercase
         * - Remove protocol (http://, https://)
         * - Remove path, query, fragment
         * - Remove trailing dot
         * - Handle www. prefix consistently (strip it)
         * - Reject malformed entries
         *
         * Returns the normalized domain, or null if the input is invalid.
         */
        fun normalize(rawInput: String): String? {
            var domain = rawInput.trim()
            if (domain.isEmpty()) return null

            // Remove protocol
            domain = domain.replace(REGEX_PROTOCOL, "")

            // Remove path, query, fragment
            val slashIndex = domain.indexOf('/')
            if (slashIndex >= 0) domain = domain.substring(0, slashIndex)
            val questionIndex = domain.indexOf('?')
            if (questionIndex >= 0) domain = domain.substring(0, questionIndex)
            val hashIndex = domain.indexOf('#')
            if (hashIndex >= 0) domain = domain.substring(0, hashIndex)

            // Remove port
            val colonIndex = domain.indexOf(':')
            if (colonIndex >= 0) domain = domain.substring(0, colonIndex)

            // Remove trailing dot
            if (domain.endsWith(".")) {
                domain = domain.removeSuffix(".")
            }

            // Lowercase
            domain = domain.lowercase()

            // Strip www. prefix
            if (domain.startsWith("www.")) {
                domain = domain.removePrefix("www.")
            }

            // Validate: must have at least one dot, no spaces, no special chars
            if (!domain.contains(".")) return null
            if (domain.contains(" ")) return null
            if (domain.contains("@")) return null
            if (domain.startsWith(".") || domain.endsWith(".")) return null
            if (domain.length > 253) return null

            // Basic label validation
            val labels = domain.split(".")
            if (labels.any { it.isEmpty() || it.length > 63 }) return null
            if (labels.any { !it.matches(REGEX_LABEL) }) return null

            // Must have at least 2 labels (e.g., "example.com")
            if (labels.size < 2) return null

            return domain
        }
    }

    init {
        require(domain.isNotBlank()) { "Domain must not be blank" }
    }
}
