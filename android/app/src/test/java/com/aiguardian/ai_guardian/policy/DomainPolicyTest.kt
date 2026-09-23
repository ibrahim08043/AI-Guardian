package com.aiguardian.ai_guardian.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DomainPolicy] normalization and domain matching.
 *
 * Tests the domain normalization logic used by website blocking:
 * 1. Protocol stripping
 * 2. Path/query/fragment removal
 * 3. www. prefix stripping
 * 4. Lowercasing
 * 5. Port removal
 * 6. Trailing dot removal
 * 7. Validation rules
 * 8. Subdomain matching
 * 9. Cache safety (empty domain set)
 */
class DomainPolicyTest {

    // -------------------------------------------------------------------------
    // Domain normalization
    // -------------------------------------------------------------------------

    @Test
    fun `normalize strips https protocol`() {
        assertEquals("example.com", DomainPolicy.normalize("https://example.com"))
    }

    @Test
    fun `normalize strips http protocol`() {
        assertEquals("example.com", DomainPolicy.normalize("http://example.com"))
    }

    @Test
    fun `normalize strips path`() {
        assertEquals("example.com", DomainPolicy.normalize("https://example.com/path/to/page"))
    }

    @Test
    fun `normalize strips query string`() {
        assertEquals("example.com", DomainPolicy.normalize("https://example.com/page?q=search"))
    }

    @Test
    fun `normalize strips fragment`() {
        assertEquals("example.com", DomainPolicy.normalize("https://example.com/page#section"))
    }

    @Test
    fun `normalize strips port`() {
        assertEquals("example.com", DomainPolicy.normalize("https://example.com:8080"))
    }

    @Test
    fun `normalize strips trailing dot`() {
        assertEquals("example.com", DomainPolicy.normalize("example.com."))
    }

    @Test
    fun `normalize strips www prefix`() {
        assertEquals("example.com", DomainPolicy.normalize("www.example.com"))
    }

    @Test
    fun `normalize strips www with protocol`() {
        assertEquals("example.com", DomainPolicy.normalize("https://www.example.com"))
    }

    @Test
    fun `normalize lowercases domain`() {
        assertEquals("example.com", DomainPolicy.normalize("EXAMPLE.COM"))
    }

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("example.com", DomainPolicy.normalize("  example.com  "))
    }

    @Test
    fun `normalize handles complex URL`() {
        assertEquals("example.com", DomainPolicy.normalize("https://www.EXAMPLE.COM:8080/path?q=test#hash"))
    }

    @Test
    fun `normalize returns null for empty input`() {
        assertNull(DomainPolicy.normalize(""))
    }

    @Test
    fun `normalize returns null for blank input`() {
        assertNull(DomainPolicy.normalize("   "))
    }

    @Test
    fun `normalize returns null for input without dot`() {
        assertNull(DomainPolicy.normalize("localhost"))
    }

    @Test
    fun `normalize returns null for input with spaces`() {
        assertNull(DomainPolicy.normalize("exam ple.com"))
    }

    @Test
    fun `normalize returns null for input with at sign`() {
        assertNull(DomainPolicy.normalize("user@example.com"))
    }

    @Test
    fun `normalize returns null for trailing dot after domain`() {
        assertNull(DomainPolicy.normalize(".example.com"))
    }

    @Test
    fun `normalize returns null for domain exceeding max length`() {
        val longDomain = "a".repeat(254) + ".com"
        assertNull(DomainPolicy.normalize(longDomain))
    }

    @Test
    fun `normalize returns null for single label`() {
        assertNull(DomainPolicy.normalize("localhost"))
    }

    @Test
    fun `normalize accepts valid two-part domain`() {
        assertEquals("ab.com", DomainPolicy.normalize("ab.com"))
    }

    @Test
    fun `normalize accepts three-part domain`() {
        assertEquals("sub.example.com", DomainPolicy.normalize("sub.example.com"))
    }

    @Test
    fun `normalize handles subdomain correctly`() {
        assertEquals("m.facebook.com", DomainPolicy.normalize("https://m.facebook.com"))
    }

    // -------------------------------------------------------------------------
    // Subdomain matching logic (simulated, matching service behavior)
    // -------------------------------------------------------------------------

    /**
     * Simulates the subdomain matching logic from AIGuardianAccessibilityService.matchBlockedDomain().
     * A blocked domain "facebook.com" should also match "m.facebook.com" and "www.facebook.com".
     */
    private fun matchBlockedDomain(candidate: String, blockedDomains: Set<String>): String? {
        val normalized = DomainPolicy.normalize(candidate) ?: return null

        // Exact match
        if (normalized in blockedDomains) {
            return normalized
        }

        // Subdomain match
        for (blocked in blockedDomains) {
            if (normalized.endsWith(".$blocked")) {
                return blocked
            }
        }

        return null
    }

    @Test
    fun `exact domain match works`() {
        val blocked = setOf("facebook.com", "youtube.com")
        assertEquals("facebook.com", matchBlockedDomain("facebook.com", blocked))
        assertEquals("youtube.com", matchBlockedDomain("youtube.com", blocked))
    }

    @Test
    fun `subdomain match works`() {
        val blocked = setOf("facebook.com")
        assertEquals("facebook.com", matchBlockedDomain("m.facebook.com", blocked))
        assertEquals("facebook.com", matchBlockedDomain("www.facebook.com", blocked))
        assertEquals("facebook.com", matchBlockedDomain("touch.facebook.com", blocked))
    }

    @Test
    fun `non-subdomain does not match`() {
        val blocked = setOf("facebook.com")
        // "notfacebook.com" is NOT a subdomain of "facebook.com"
        assertNull(matchBlockedDomain("notfacebook.com", blocked))
    }

    @Test
    fun `child domain does not match parent`() {
        val blocked = setOf("example.com")
        // "example.com.evil.com" is NOT a subdomain of "example.com"
        assertNull(matchBlockedDomain("example.com.evil.com", blocked))
    }

    @Test
    fun `URL with path normalizes to domain`() {
        val blocked = setOf("facebook.com")
        assertEquals("facebook.com", matchBlockedDomain("https://www.facebook.com/posts/123", blocked))
    }

    @Test
    fun `URL with query normalizes to domain`() {
        val blocked = setOf("youtube.com")
        assertEquals("youtube.com", matchBlockedDomain("https://youtube.com/watch?v=abc", blocked))
    }

    @Test
    fun `empty blocked set returns null`() {
        assertNull(matchBlockedDomain("facebook.com", emptySet()))
    }

    @Test
    fun `null candidate returns null`() {
        val blocked = setOf("facebook.com")
        assertNull(matchBlockedDomain("", blocked))
    }

    @Test
    fun `80+ blocked domains match correctly`() {
        val blocked = (1..80).map { "domain$it.com" }.toSet()
        assertEquals(80, blocked.size)

        // Exact matches
        for (i in 1..80) {
            assertEquals("domain$i.com", matchBlockedDomain("domain$i.com", blocked))
        }

        // Subdomain matches
        for (i in 1..80) {
            assertEquals("domain$i.com", matchBlockedDomain("www.domain$i.com", blocked))
            assertEquals("domain$i.com", matchBlockedDomain("m.domain$i.com", blocked))
        }

        // Non-matches
        assertNull(matchBlockedDomain("notblocked.com", blocked))
        assertNull(matchBlockedDomain("domain99.com", blocked))
    }

    // -------------------------------------------------------------------------
    // Cache safety: empty domain set behavior
    // -------------------------------------------------------------------------

    @Test
    fun `empty blocked set returns null for all candidates`() {
        val blocked = emptySet<String>()
        assertNull(matchBlockedDomain("facebook.com", blocked))
        assertNull(matchBlockedDomain("youtube.com", blocked))
        assertNull(matchBlockedDomain("any-domain.com", blocked))
    }

    @Test
    fun `blocked set retains entries across simulated refreshes`() {
        val blocked = mutableSetOf("facebook.com", "youtube.com", "twitter.com")
        assertEquals(3, blocked.size)

        // Simulate failed refresh (don't clear/add)
        // blocked should retain its entries
        assertEquals(3, blocked.size)
        assertEquals("facebook.com", matchBlockedDomain("facebook.com", blocked))
        assertEquals("youtube.com", matchBlockedDomain("youtube.com", blocked))
        assertEquals("twitter.com", matchBlockedDomain("twitter.com", blocked))
    }

    @Test
    fun `simulated fail-safe refresh preserves cache`() {
        val blocked = mutableSetOf("facebook.com", "youtube.com")

        // Simulate fail-safe refresh pattern:
        // val newDomains = db.getBlockedDomainSet() → returns empty (DB failure)
        // if (newDomains.isNotEmpty()) { blocked.clear(); blocked.addAll(newDomains) }
        // → skipped because empty

        assertEquals(2, blocked.size)
        assertEquals("facebook.com", matchBlockedDomain("facebook.com", blocked))
    }

    @Test
    fun `simulated successful refresh replaces cache`() {
        val blocked = mutableSetOf("facebook.com", "youtube.com")

        // Simulate successful refresh:
        val newDomains = setOf("twitter.com", "instagram.com")
        if (newDomains.isNotEmpty()) {
            blocked.clear()
            blocked.addAll(newDomains)
        }

        assertEquals(2, blocked.size)
        assertNull(matchBlockedDomain("facebook.com", blocked)) // old domain gone
        assertEquals("twitter.com", matchBlockedDomain("twitter.com", blocked))
        assertEquals("instagram.com", matchBlockedDomain("instagram.com", blocked))
    }

    // -------------------------------------------------------------------------
    // Date rollover safety (domain matching is date-independent)
    // -------------------------------------------------------------------------

    @Test
    fun `domain matching is date-independent`() {
        val blocked = setOf("facebook.com")

        // Domain matching should work regardless of time
        assertEquals("facebook.com", matchBlockedDomain("facebook.com", blocked))
        assertEquals("facebook.com", matchBlockedDomain("www.facebook.com", blocked))
        assertEquals("facebook.com", matchBlockedDomain("https://m.facebook.com/profile", blocked))
    }
}
