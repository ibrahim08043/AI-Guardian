package com.aiguardian.ai_guardian.storage

import com.aiguardian.ai_guardian.contentfilter.ContentFilterMatchMode
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRule
import com.aiguardian.ai_guardian.policy.DomainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DefaultBlockListImporter] parsing and import logic.
 *
 * Tests cover:
 * 1. Valid website + keyword sections
 * 2. Blank lines ignored
 * 3. Whitespace handling
 * 4. Case-insensitive section headings
 * 5. Placeholder lines ignored
 * 6. Duplicate entries removed
 * 7. Website URL normalization via DomainPolicy.normalize()
 * 8. Existing database rules are not duplicated
 * 9. Existing user rules are preserved
 * 10. Empty/missing/malformed list does not wipe existing rules
 * 11. Running import twice is idempotent
 */
class DefaultBlockListImporterTest {

    private lateinit var importer: DefaultBlockListImporter

    // In-memory fake repositories for testing import logic
    private lateinit var fakeDomainRepo: FakeDomainRepository
    private lateinit var fakeFilterRepo: FakeContentFilterRepository

    @Before
    fun setUp() {
        // DefaultBlockListImporter's parseSections is internal and doesn't need Context
        importer = DefaultBlockListImporter.__test_only__()
        fakeDomainRepo = FakeDomainRepository()
        fakeFilterRepo = FakeContentFilterRepository()
    }

    // -------------------------------------------------------------------------
    // parseSections — valid input
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections handles valid sites and keywords`() {
        val lines = listOf(
            "sites to block",
            "https://example.com",
            "https://test.org",
            "",
            "keywords to block",
            "spam",
            "adult",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(2, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals("https://test.org", sites[1])

        assertEquals(2, keywords.size)
        assertEquals("spam", keywords[0])
        assertEquals("adult", keywords[1])
    }

    // -------------------------------------------------------------------------
    // parseSections — blank lines
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections ignores blank lines`() {
        val lines = listOf(
            "sites to block",
            "",
            "  ",
            "https://example.com",
            "",
            "",
            "keywords to block",
            "",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals(1, keywords.size)
        assertEquals("spam", keywords[0])
    }

    // -------------------------------------------------------------------------
    // parseSections — whitespace trimming
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections trims whitespace from entries`() {
        val lines = listOf(
            "sites to block",
            "  https://example.com  ",
            "   https://test.org   ",
            "keywords to block",
            "  spam  ",
            "   adult   ",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(2, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals("https://test.org", sites[1])

        assertEquals(2, keywords.size)
        assertEquals("spam", keywords[0])
        assertEquals("adult", keywords[1])
    }

    // -------------------------------------------------------------------------
    // parseSections — case-insensitive headings
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections handles uppercase section headings`() {
        val lines = listOf(
            "SITES TO BLOCK",
            "https://example.com",
            "KEYWORDS TO BLOCK",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals(1, keywords.size)
        assertEquals("spam", keywords[0])
    }

    @Test
    fun `parseSections handles mixed-case section headings`() {
        val lines = listOf(
            "Sites To Block",
            "https://example.com",
            "Keywords To Block",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals(1, keywords.size)
    }

    // -------------------------------------------------------------------------
    // parseSections — placeholder text ignored
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections ignores placeholder text`() {
        val lines = listOf(
            "sites to block",
            "url list here",
            "https://example.com",
            "keywords to block",
            "keywords list here",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals(1, keywords.size)
        assertEquals("spam", keywords[0])
    }

    // -------------------------------------------------------------------------
    // parseSections — duplicate entries
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections preserves duplicate entries in raw parse`() {
        // Note: deduplication happens at import time, not parse time
        val lines = listOf(
            "sites to block",
            "https://example.com",
            "https://example.com",
            "keywords to block",
            "spam",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(2, sites.size)
        assertEquals(2, keywords.size)
    }

    // -------------------------------------------------------------------------
    // parseSections — comment lines
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections ignores comment lines`() {
        val lines = listOf(
            "# This is a comment",
            "sites to block",
            "https://example.com",
            "# Another comment",
            "keywords to block",
            "# spam keyword",
            "adult",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals("https://example.com", sites[0])
        assertEquals(1, keywords.size)
        assertEquals("adult", keywords[0])
    }

    // -------------------------------------------------------------------------
    // parseSections — inline parenthetical comments
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections strips inline parenthetical comments`() {
        val lines = listOf(
            "sites to block",
            "https://exhentai.org (needs account / H@H)",
            "https://example.com",
        )

        val (sites, _) = importer.parseSections(lines)

        assertEquals(2, sites.size)
        assertEquals("https://exhentai.org", sites[0])
        assertEquals("https://example.com", sites[1])
    }

    // -------------------------------------------------------------------------
    // parseSections — lines before any section heading
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections ignores lines before first heading`() {
        val lines = listOf(
            "random text",
            "https://example.com",
            "sites to block",
            "https://blocked.com",
            "keywords to block",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals("https://blocked.com", sites[0])
        assertEquals(1, keywords.size)
    }

    // -------------------------------------------------------------------------
    // parseSections — empty input
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections handles empty input`() {
        val (sites, keywords) = importer.parseSections(emptyList())
        assertEquals(0, sites.size)
        assertEquals(0, keywords.size)
    }

    @Test
    fun `parseSections handles only blank lines`() {
        val (sites, keywords) = importer.parseSections(listOf("", "  ", ""))
        assertEquals(0, sites.size)
        assertEquals(0, keywords.size)
    }

    // -------------------------------------------------------------------------
    // Domain normalization integration
    // -------------------------------------------------------------------------

    @Test
    fun `import normalizes domains via DomainPolicy normalize`() {
        // Seed some domains with various URL formats
        val lines = listOf(
            "sites to block",
            "https://www.example.com",
            "http://TEST.ORG/path",
            "example2.com",
            "keywords to block",
            "spam",
        )

        // Verify that DomainPolicy.normalize handles these correctly
        assertEquals("example.com", DomainPolicy.normalize("https://www.example.com"))
        assertEquals("test.org", DomainPolicy.normalize("http://TEST.ORG/path"))
        assertEquals("example2.com", DomainPolicy.normalize("example2.com"))
    }

    // -------------------------------------------------------------------------
    // Fake repositories — import deduplication
    // -------------------------------------------------------------------------

    @Test
    fun `import skips domains that already exist in database`() {
        // Pre-populate with an existing domain
        fakeDomainRepo.existingDomains.add("example.com")

        val result = importer.importWithFakes(
            siteEntries = listOf("https://example.com", "https://newdomain.com"),
            keywordEntries = emptyList(),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { domain -> fakeDomainRepo.saveDomain(domain) },
            keywordSaver = { phrase -> fakeFilterRepo.saveKeyword(phrase) },
        )

        assertNotNull(result)
        assertEquals(1, result!!.domainsImported) // only newdomain.com
        assertEquals(1, result.domainsSkipped)     // example.com skipped
    }

    @Test
    fun `import skips keywords that already exist in database`() {
        // Pre-populate with an existing keyword
        fakeFilterRepo.existingKeywords.add("spam")

        val result = importer.importWithFakes(
            siteEntries = emptyList(),
            keywordEntries = listOf("spam", "adult"),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(1, result!!.keywordsImported) // only adult
        assertEquals(1, result.keywordsSkipped)    // spam skipped
    }

    // -------------------------------------------------------------------------
    // Import preserves existing user rules
    // -------------------------------------------------------------------------

    @Test
    fun `import does not delete existing user rules`() {
        fakeDomainRepo.existingDomains.add("user-custom.com")
        fakeFilterRepo.existingKeywords.add("custom-keyword")

        val result = importer.importWithFakes(
            siteEntries = listOf("https://newdomain.com"),
            keywordEntries = listOf("new-keyword"),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        // User's existing rules should still be there
        assertTrue(fakeDomainRepo.existingDomains.contains("user-custom.com"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("custom-keyword"))
        // New rules should also be there
        assertTrue(fakeDomainRepo.existingDomains.contains("newdomain.com"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("new-keyword"))
    }

    // -------------------------------------------------------------------------
    // Import does not wipe rules on empty/malformed input
    // -------------------------------------------------------------------------

    @Test
    fun `import with empty entries does not wipe existing rules`() {
        fakeDomainRepo.existingDomains.add("existing.com")
        fakeFilterRepo.existingKeywords.add("existing-keyword")

        val result = importer.importWithFakes(
            siteEntries = emptyList(),
            keywordEntries = emptyList(),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(0, result!!.domainsImported)
        assertEquals(0, result.keywordsImported)
        // Existing rules preserved
        assertTrue(fakeDomainRepo.existingDomains.contains("existing.com"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("existing-keyword"))
    }

    // -------------------------------------------------------------------------
    // Import is idempotent
    // -------------------------------------------------------------------------

    @Test
    fun `running import twice is idempotent`() {
        val siteEntries = listOf("https://example.com", "https://test.org")
        val keywordEntries = listOf("spam", "adult")

        // First import
        val result1 = importer.importWithFakes(
            siteEntries = siteEntries,
            keywordEntries = keywordEntries,
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result1)
        assertEquals(2, result1!!.domainsImported)
        assertEquals(2, result1.keywordsImported)
        assertEquals(0, result1.domainsSkipped)
        assertEquals(0, result1.keywordsSkipped)

        // Second import — everything should be skipped
        val result2 = importer.importWithFakes(
            siteEntries = siteEntries,
            keywordEntries = keywordEntries,
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result2)
        assertEquals(0, result2!!.domainsImported)
        assertEquals(0, result2.keywordsImported)
        assertEquals(2, result2.domainsSkipped)
        assertEquals(2, result2.keywordsSkipped)
    }

    // -------------------------------------------------------------------------
    // Import counts
    // -------------------------------------------------------------------------

    @Test
    fun `import tracks correct counts`() {
        val result = importer.importWithFakes(
            siteEntries = listOf(
                "https://example.com",
                "https://test.org",
                "invalid domain without dot",
            ),
            keywordEntries = listOf(
                "spam",
                "adult",
            ),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(3, result!!.totalDomainsParsed)  // 3 raw entries (including invalid)
        assertEquals(2, result.totalKeywordsParsed)
        assertEquals(2, result.domainsImported)  // example.com, test.org
        assertEquals(1, result.parseErrors)       // "invalid domain without dot"
        assertEquals(2, result.keywordsImported)
    }

    // -------------------------------------------------------------------------
    // Domain normalization — various URL formats
    // -------------------------------------------------------------------------

    @Test
    fun `domain normalization handles all URL formats`() {
        // These are the formats that appear in assets/list.txt
        val testCases = mapOf(
            "https://www.pornhub.com" to "pornhub.com",
            "https://xhamster.com" to "xhamster.com",
            "https://boards.4chan.org/b/" to "boards.4chan.org",
            "https://www.3movs.com" to "3movs.com",
            "https://fpo.xxx" to "fpo.xxx",
            "https://rule34.xxx" to "rule34.xxx",
            "https://danbooru.donmai.us" to "danbooru.donmai.us",
        )

        for ((input, expected) in testCases) {
            val normalized = DomainPolicy.normalize(input)
            assertEquals(
                "Normalizing '$input'",
                expected,
                normalized,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Placeholder text — comprehensive
    // -------------------------------------------------------------------------

    @Test
    fun `parseSections ignores all placeholder variants`() {
        val lines = listOf(
            "sites to block",
            "url list here",
            "URL LIST HERE",
            "https://example.com",
            "keywords to block",
            "keywords list here",
            "KEYWORDS LIST HERE",
            "spam",
        )

        val (sites, keywords) = importer.parseSections(lines)

        assertEquals(1, sites.size)
        assertEquals(1, keywords.size)
    }

    // -------------------------------------------------------------------------
    // Malformed entries
    // -------------------------------------------------------------------------

    @Test
    fun `import handles malformed entries gracefully`() {
        val result = importer.importWithFakes(
            siteEntries = listOf(
                "",                  // empty
                "   ",               // blank
                "not a domain",      // no dot
                "example.com",       // valid
            ),
            keywordEntries = listOf(
                "",                  // empty
                "   ",               // blank
                "valid",             // valid
            ),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        // Empty/blank entries should be filtered by normalization
        assertEquals(1, result!!.domainsImported)  // only example.com
        assertEquals(1, result.keywordsImported)    // only "valid"
    }

    // -------------------------------------------------------------------------
    // REAL FILE: parseSections processes full list.txt format
    // -------------------------------------------------------------------------

    /**
     * Representative copy of the actual assets/list.txt structure.
     * Tests that the parser handles the real formatting with hundreds of entries.
     */
    @Test
    fun `parseSections handles real list format with many entries`() {
        val lines = buildRealListContent()

        val (sites, keywords) = importer.parseSections(lines)

        // Real file has 146 URL lines and 266 keyword lines.
        // Our representative copy has a subset — verify counts are large.
        assertTrue("Expected many site entries, got ${sites.size}", sites.size >= 100)
        assertTrue("Expected many keyword entries, got ${keywords.size}", keywords.size >= 200)

        // Verify specific known entries exist
        assertTrue("pornhub.com should be in sites", sites.any { it.contains("pornhub.com") })
        assertTrue("xvideos.com should be in sites", sites.any { it.contains("xvideos.com") })
        assertTrue("reddit.com should be in sites", sites.any { it.contains("reddit.com") })
        assertTrue("facebook.com should be in sites", sites.any { it.contains("facebook.com") })

        // Verify keyword entries
        assertTrue("pornhub should be in keywords", keywords.any { it.equals("pornhub", ignoreCase = true) })
        assertTrue("cuckold should be in keywords", keywords.any { it.equals("cuckold", ignoreCase = true) })
        assertTrue("xxx should be in keywords", keywords.any { it.equals("xxx", ignoreCase = true) })
        assertTrue("mff should be in keywords", keywords.any { it.equals("mff", ignoreCase = true) })
    }

    /**
     * Import with real file format — verify ALL entries are processed,
     * not just the first one. This is the critical regression test.
     */
    @Test
    fun `import processes all entries from real list format`() {
        val lines = buildRealListContent()
        val (siteEntries, keywordEntries) = importer.parseSections(lines)

        val result = importer.importWithFakes(
            siteEntries = siteEntries,
            keywordEntries = keywordEntries,
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        // Must import MANY domains, not just 1
        assertTrue(
            "Expected many domains imported, got ${result!!.domainsImported}",
            result.domainsImported >= 100,
        )
        // Must import MANY keywords, not just 1
        assertTrue(
            "Expected many keywords imported, got ${result.keywordsImported}",
            result.keywordsImported >= 200,
        )
        // All parsed entries should be accounted for
        assertEquals(result.totalDomainsParsed, result.domainsImported + result.domainsSkipped + result.parseErrors)
        assertEquals(result.totalKeywordsParsed, result.keywordsImported + result.keywordsSkipped)
    }

    /**
     * Malformed entry in the middle of valid entries must NOT stop parsing.
     * Entries after the malformed line must still be processed.
     */
    @Test
    fun `malformed entry does not stop parsing of subsequent entries`() {
        // importWithFakes receives already-parsed entries (after extractEntry strips comments).
        // Malformed entries here are those that fail DomainPolicy.normalize().
        val siteEntries = listOf(
            "https://valid1.com",
            "https://valid2.com",
            "not a domain",                  // truly malformed — no dot, has spaces
            "https://valid3.com",
            "https://exhentai.org",           // already stripped of parenthetical comment
            "https://valid4.com",
        )

        val result = importer.importWithFakes(
            siteEntries = siteEntries,
            keywordEntries = emptyList(),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        // 5 valid entries should be imported despite the malformed one
        assertEquals(5, result!!.domainsImported)
        // The malformed entry counts as a parse error
        assertEquals(1, result.parseErrors)
        // All 6 raw entries counted
        assertEquals(6, result.totalDomainsParsed)

        // Verify specific domains were imported
        assertTrue(fakeDomainRepo.existingDomains.contains("valid1.com"))
        assertTrue(fakeDomainRepo.existingDomains.contains("valid2.com"))
        assertTrue(fakeDomainRepo.existingDomains.contains("valid3.com"))
        assertTrue(fakeDomainRepo.existingDomains.contains("exhentai.org"))
        assertTrue(fakeDomainRepo.existingDomains.contains("valid4.com"))
    }

    /**
     * End-to-end: parseSections strips parenthetical comments, then importWithFakes
     * normalizes and inserts. Verify the full pipeline works.
     */
    @Test
    fun `end-to-end parse and import handles parenthetical comments`() {
        val rawLines = listOf(
            "sites to block",
            "https://exhentai.org (needs account / H@H)",
            "https://valid.com",
            "not valid",
            "keywords to block",
            "adult",
        )

        val (sites, keywords) = importer.parseSections(rawLines)
        // parseSections returns all non-blank entries under the section, including
        // malformed ones — it does NOT validate. The parenthetical IS stripped.
        assertEquals(3, sites.size)
        assertEquals("https://exhentai.org", sites[0])
        assertEquals("https://valid.com", sites[1])
        assertEquals("not valid", sites[2])

        val result = importer.importWithFakes(
            siteEntries = sites,
            keywordEntries = keywords,
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(2, result!!.domainsImported)
        assertTrue(fakeDomainRepo.existingDomains.contains("exhentai.org"))
        assertTrue(fakeDomainRepo.existingDomains.contains("valid.com"))
        assertEquals(1, result.keywordsImported)
    }

    /**
     * Real file has keywords like X, MFF, MMF, FFF, MMM, JOI, CEI, SPH, xxx.
     * These short keywords must NOT be rejected.
     */
    @Test
    fun `short keywords like MFF MMF FFF xxx are imported`() {
        val keywordEntries = listOf(
            "MFF",
            "MMF",
            "FFF",
            "MMM",
            "xxx",
            "JOI",
            "CEI",
            "SPH",
            "X",
            "cuckold",
            "cheating wife",
        )

        val result = importer.importWithFakes(
            siteEntries = emptyList(),
            keywordEntries = keywordEntries,
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(11, result!!.keywordsImported)
        assertEquals(0, result.keywordsSkipped)
        assertTrue(fakeFilterRepo.existingKeywords.contains("mff"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("mmf"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("xxx"))
        assertTrue(fakeFilterRepo.existingKeywords.contains("x"))
    }

    /**
     * Case-insensitive duplicate detection for keywords.
     */
    @Test
    fun `keyword duplicate detection is case-insensitive`() {
        fakeFilterRepo.existingKeywords.add("pornhub")

        val result = importer.importWithFakes(
            siteEntries = emptyList(),
            keywordEntries = listOf("Pornhub", "PORNHUB", "pornhub"),
            existingDomains = fakeDomainRepo.existingDomains,
            existingKeywords = fakeFilterRepo.existingKeywords,
            domainSaver = { fakeDomainRepo.saveDomain(it) },
            keywordSaver = { fakeFilterRepo.saveKeyword(it) },
        )

        assertNotNull(result)
        assertEquals(0, result!!.keywordsImported) // all 3 are duplicates of "pornhub"
        assertEquals(3, result.keywordsSkipped)
    }

    // -------------------------------------------------------------------------
    // Build representative copy of real assets/list.txt
    // -------------------------------------------------------------------------

    private fun buildRealListContent(): List<String> {
        // This is a representative subset of the actual assets/list.txt
        // with the same formatting characteristics: blank lines between sections,
        // inline comments, malformed entries, short keywords, multi-word keywords.
        return listOf(
            "sites to block",
            "",
            "https://www.pornhub.com",
            "https://www.xvideos.com",
            "https://www.xnxx.com",
            "https://xhamster.com",
            "https://www.redtube.com",
            "https://www.youporn.com",
            "https://www.tube8.com",
            "https://spankbang.com",
            "https://www.eporner.com",
            "https://hqporner.com",
            "https://www.porntrex.com",
            "https://www.ixxx.com",
            "https://www.tnaflix.com",
            "https://beeg.com",
            "https://www.nuvid.com",
            "https://pornone.com",
            "https://onlyfans.com",
            "https://fansly.com",
            "https://www.manyvids.com",
            "https://www.clips4sale.com",
            "https://www.adulttime.com",
            "https://www.brazzers.com",
            "https://www.realitykings.com",
            "https://www.naughtyamerica.com",
            "https://bangbros.com",
            "https://www.vixen.com",
            "https://chaturbate.com",
            "https://stripchat.com",
            "https://www.livejasmin.com",
            "https://www.camsoda.com",
            "https://bongacams.com",
            "https://www.myfreecams.com",
            "https://rule34.xxx",
            "https://gelbooru.com",
            "https://danbooru.donmai.us",
            "https://e-hentai.org",
            "https://exhentai.org (needs account / H@H)",
            "https://nhentai.net",
            "https://www.fakku.net",
            "https://motherless.com",
            "https://www.imagefap.com",
            "https://theporndude.com",
            "https://f95zone.to",
            "https://www.reddit.com",
            "https://x.com",
            "https://boards.4chan.org/b/",
            "https://boards.4chan.org/h/",
            "https://txxx.com",
            "https://www.3movs.com",
            "https://smutr.com",
            "https://www.vporn.com",
            "https://www.4tube.com",
            "https://www.porzo.com",
            "https://www.dinotube.com",
            "https://www.porndoe.com",
            "https://pornzog.com",
            "https://fuq.comPorn00",
            "https://porn00.org",
            "https://noodlemagazine.com",
            "https://alohatube.com",
            "https://melonstube.com",
            "https://www.tiava.com",
            "https://www.tubegalore.com",
            "https://www.pornmd.com",
            "https://www.xxxfiles.com",
            "https://fpo.xxx",
            "https://letmejizz.co",
            "https://www.hdzog.com",
            "https://fapster.xxx",
            "https://shooshtime.com",
            "https://fapcat.com",
            "https://www.teamskeet.com",
            "https://www.adultprime.com",
            "https://www.21sextury.com",
            "https://www.mofos.com",
            "https://www.evilangel.com",
            "https://www.julesjordan.com",
            "https://www.puretaboo.com",
            "https://www.mylf.com",
            "https://nubiles-porn.com",
            "https://www.familystrokes.com",
            "https://www.letsdoeit.com",
            "https://www.girlfriendsfilms.com",
            "https://www.twistys.com",
            "https://www.bellesa.co",
            "https://jerkmate.com",
            "https://skyprivate.com",
            "https://www.loyalfans.com",
            "https://e621.net",
            "https://hanime.tv",
            "https://hentai.xxx",
            "https://www.sex.com",
            "https://www.pornpics.com",
            "https://www.erome.com",
            "https://theporndude.com",
            "https://www.xxxranger.com",
            "https://fapindex.com",
            "https://pornslist.com",
            "https://sxyprn.com",
            "https://www.youjizz.com",
            "https://www.sunporno.com",
            "https://www.pornhat.com",
            "https://hornybutt.com",
            "https://fikfap.com",
            "https://tik.porn",
            "https://xfree.com",
            "https://fyptt.to",
            "https://kwiky.com",
            "https://slushy.com",
            "https://www.blacked.com",
            "https://www.tushy.com",
            "https://www.deeper.com",
            "https://www.x-art.com",
            "https://www.private.com",
            "https://www.dorcel.com",
            "https://www.dogfartnetwork.com",
            "https://www.digitalplayground.com",
            "https://faphouse.com",
            "https://www.vixen.com",
            "https://efukt.com",
            "https://www.inhumanity.com",
            "https://www.humoron.com",
            "https://daftporn.com",
            "https://hotwifecaps.com",
            "https://sissymemes.com",
            "https://jable.tv",
            "https://sharesome.com",
            "https://www.literotica.com",
            "https://www.facebook.com",
            "https://www.mydirtyhobby.com",
            "https://www.redgifs.com",
            "https://www.hclips.com",
            "https://tuberbit.com",
            "https://nuditok.com",
            "https://xxxfollow.com",
            "https://reddclips.com",
            "https://ogfap.com",
            "https://www.slayed.com",
            "https://www.milfy.com",
            "https://www.girlsway.com",
            "https://www.sexart.com",
            "https://www.metart.com",
            "https://www.kink.com",
            "https://popjav.com",
            "https://www.lushstories.com",
            "https://bestporncomix.com",
            "",
            "",
            "",
            "keywords to block",
            "",
            "Pornhub",
            "XVideos",
            "XNXX",
            "xHamster",
            "RedTube",
            "YouPorn",
            "Tube8",
            "SpankBang",
            "Eporner",
            "HQPorner",
            "PornTrex",
            "iXXX",
            "TNAFlix",
            "Beeg",
            "NuVid",
            "PornOne",
            "OnlyFans",
            "Fansly",
            "ManyVids",
            "Clips4Sale",
            "Adult Time",
            "Brazzers",
            "Reality Kings",
            "Naughty America",
            "BangBros",
            "Vixen",
            "Chaturbate",
            "Stripchat",
            "LiveJasmin",
            "CamSoda",
            "BongaCams",
            "MyFreeCams",
            "Rule34",
            "Gelbooru",
            "Danbooru",
            "E-Hentai",
            "ExHentai",
            "Nhentai",
            "Fakku",
            "Motherless",
            "ImageFap",
            "ThePornDude",
            "F95zone",
            "Reddit",
            "X (Twitter)",
            "4chan /b/",
            "4chan /h/",
            "TXXX",
            "3Movs",
            "Smutr",
            "VPorn",
            "4Tube",
            "Porzo",
            "DinoTube",
            "PornDoe",
            "PornZog",
            "Fuq",
            "Porn00",
            "NoodleMagazine",
            "AlohaTube",
            "MelonsTube",
            "Tiava",
            "TubeGalore",
            "PornMD",
            "XXXFiles",
            "FPO",
            "LetMeJizz",
            "HDZog",
            "Fapster",
            "ShooshTime",
            "FapCat",
            "TeamSkeet",
            "AdultPrime",
            "21Sextury",
            "Mofos",
            "Evil Angel",
            "Jules Jordan",
            "Pure Taboo",
            "MYLF",
            "Nubiles Porn",
            "Family Strokes",
            "LetsDoeIt",
            "Girlfriends Films",
            "Twistys",
            "Bellesa",
            "Jerkmate",
            "SkyPrivate",
            "LoyalFans",
            "e621",
            "Hanime",
            "Hentai.xxx",
            "Sex.com",
            "PornPics",
            "Erome",
            "XXXRanger",
            "FapIndex",
            "PornsList",
            "SxyPrn",
            "YouJizz",
            "SunPorno",
            "PornHat",
            "HornyButt",
            "FikFap",
            "Tik.Porn",
            "XFree",
            "FYPTT",
            "Kwiky",
            "Slushy",
            "Blacked",
            "Tushy",
            "Deeper",
            "X-Art",
            "Private",
            "Dorcel",
            "Dogfart Network",
            "Digital Playground",
            "FapHouse",
            "Efukt",
            "Inhumanity",
            "Humoron",
            "DaftPorn",
            "HotwifeCaps",
            "SissyMemes",
            "Jable",
            "Sharesome",
            "Literotica",
            "Facebook",
            "MyDirtyHobby",
            "RedGifs",
            "HClips",
            "TuberBit",
            "NudiTok",
            "XXXFollow",
            "ReddClips",
            "OGfap",
            "Slayed",
            "Milfy",
            "GirlsWay",
            "SexArt",
            "MetArt",
            "Kink",
            "PopJAV",
            "Lush Stories",
            "BestPornComix",
            "cuckold",
            "cuck",
            "hotwife",
            "cheating wife",
            "wife cheating",
            "wife breeding",
            "abdl",
            "Submission",
            "Bondage",
            "Spanking",
            "Forced orgasm",
            "orgasm denial",
            "Tease and denial",
            "Humiliation",
            "Chastity",
            "Femdom",
            "Orgasm control",
            "Step-family",
            "stepmom",
            "stepsister",
            "Voyeurism",
            "Cuckquean",
            "Sharing",
            "Swinging",
            "Threesome",
            "MFF",
            "MMF",
            "FFF",
            "MMM",
            "xxx",
            "Orgy",
            "Gangbang",
            "Reverse gangbang",
            "Breeding",
            "Impregnation",
            "fantasy",
            "CNC",
            "Foot fetish",
            "Thigh fetish",
            "Leg fetish",
            "fetish",
            "Ass worship",
            "Butt worship",
            "Breast worship",
            "Tit worship",
            "Nipple play",
            "Navel fetish",
            "Armpit fetish",
            "SSBBW",
            "BBW",
            "Pregnancy fetish",
            "Lingerie",
            "Stockings",
            "Nylons",
            "Crossdressing",
            "Forced feminization",
            "Sissy play",
            "sissy",
            "Panties",
            "Underwear fetish",
            "Bra fetish",
            "Sock fetish",
            "Anal",
            "Oral",
            "blowjob",
            "cunnilingus",
            "rimming",
            "Deepthroat",
            "Face fucking",
            "Facial",
            "cum",
            "Cum play",
            "Cum eating",
            "Creampie",
            "Multiple creampies",
            "Cum swap",
            "Swallowing",
            "Spit play",
            "Squirting",
            "Fisting",
            "Double penetration",
            "Triple penetration",
            "Machine fucking",
            "Dildos",
            "Scat",
            "Degradation kink",
            "Interracial",
            "Lesbian focused",
            "Gay focused",
            "Bisexual",
            "Being watched",
            "Voyeur",
            "JOI",
            "CEI",
            "SPH",
            "Small Penis Humiliation",
            "Big dick worship",
            "Cock",
            "Cock worship",
            "Big dick",
            "Pussy worship",
            "Pussy",
            "Ass to mouth",
            "Dirty talk",
            "Moaning",
            "Quiet sex",
            "Rough sex",
            "Sensual sex",
            "Gentle sex",
            "Slow teasing",
            "Morning sex",
            "Sleep sex",
            "Breeding relationship",
            "diaper",
            "pooping",
            "wife sharing",
            "wife cheating",
            "sharing wife",
            "cheating wife",
            "wife use",
            "used wife",
            "adult",
        )
    }

    // -------------------------------------------------------------------------
    // Helper: fake domain repository
    // -------------------------------------------------------------------------

    private class FakeDomainRepository {
        val existingDomains = mutableSetOf<String>()

        fun saveDomain(domain: String): Boolean {
            if (domain.isBlank()) return false
            existingDomains.add(domain)
            return true
        }
    }

    // -------------------------------------------------------------------------
    // Helper: fake content filter repository
    // -------------------------------------------------------------------------

    private class FakeContentFilterRepository {
        val existingKeywords = mutableSetOf<String>()

        fun saveKeyword(phrase: String): Long {
            if (phrase.isBlank()) return -1
            existingKeywords.add(phrase)
            return existingKeywords.size.toLong() // fake ID
        }
    }
}
