package com.aiguardian.ai_guardian.storage

import android.content.Context
import android.util.Log
import com.aiguardian.ai_guardian.contentfilter.ContentFilterMatchMode
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRepository
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRule
import com.aiguardian.ai_guardian.policy.DomainPolicy

/**
 * Imports default website and keyword block lists from assets/list.txt
 * into the existing [DomainRepository] and [ContentFilterRepository].
 *
 * This is a seeder — it only inserts rules that do not already exist.
 * It never deletes or overwrites user-created rules.
 *
 * ## Parsing
 *
 * The file has two sections:
 * - `sites to block` — one URL/domain per line
 * - `keywords to block` — one keyword/phrase per line
 *
 * Section headings are case-insensitive. Lines are trimmed; blank lines,
 * comments (lines starting with `#`), and the placeholder text itself are
 * ignored.
 *
 * ## Idempotency
 *
 * Running the import multiple times is safe:
 * - Domains are normalized via [DomainPolicy.normalize] and checked for
 *   existence before insert.
 * - Keywords are normalized via [ContentFilterRule.normalizePhrase] and
 *   checked by phrase + matchMode before insert.
 *
 * ## Fail-safe
 *
 * If the asset file is missing, empty, or malformed, existing database
 * rules are never touched.
 */
class DefaultBlockListImporter(private val context: Context?) {

    companion object {
        private const val TAG = "AIGuardianBlockListImport"
        private const val ASSET_PATH = "list.txt"

        // Section heading markers (case-insensitive matching)
        private val SECTION_SITES = "sites to block"
        private val SECTION_KEYWORDS = "keywords to block"

        // Placeholder text to ignore
        private val PLACEHOLDERS = setOf(
            "url list here",
            "keywords list here",
            "sites to block",
            "keywords to block",
        )

        /**
         * Create an importer for unit testing (no Android Context required).
         * Only [parseSections] and [importWithFakes] are available.
         */
        fun __test_only__(): DefaultBlockListImporter {
            return DefaultBlockListImporter(null)
        }
    }

    /**
     * Result summary of the import operation.
     */
    data class ImportResult(
        val domainsImported: Int,
        val domainsSkipped: Int, // duplicates already in DB
        val keywordsImported: Int,
        val keywordsSkipped: Int, // duplicates already in DB
        val parseErrors: Int,
        val totalDomainsParsed: Int,
        val totalKeywordsParsed: Int,
    )

    /**
     * Import the default block list from assets/list.txt.
     *
     * @param domainRepository Existing domain blocking repository
     * @param contentFilterRepository Existing content filter repository
     * @return [ImportResult] summary, or null if the file couldn't be loaded
     */
    fun import(
        domainRepository: DomainRepository,
        contentFilterRepository: ContentFilterRepository,
    ): ImportResult? {
        Log.i(TAG, "Starting default block list import")

        // Step 1: Read the asset file
        val lines = readAssetFile()
        if (lines == null) {
            Log.w(TAG, "Could not read assets/$ASSET_PATH — skipping import")
            return null
        }

        if (lines.isEmpty()) {
            Log.w(TAG, "assets/$ASSET_PATH is empty — skipping import")
            return null
        }

        Log.i(TAG, "Read ${lines.size} total lines from assets/$ASSET_PATH")

        // Step 2: Parse into sections
        val (siteEntries, keywordEntries) = parseSections(lines)
        Log.i(TAG, "Parsed ${siteEntries.size} site entries, ${keywordEntries.size} keyword entries")

        // Step 3: Load existing rules for deduplication
        // Must use toMutableSet() — toSet() returns an unmodifiable set,
        // and calling add() on it throws UnsupportedOperationException,
        // which kills the entire import after the first successful insert.
        val existingDomains = domainRepository.getAllDomains()
            .map { it.domain }.toMutableSet()
        val existingKeywords = contentFilterRepository.getAllRules()
            .map { it.phrase }.toMutableSet()
        Log.i(TAG, "Existing DB: ${existingDomains.size} domains, ${existingKeywords.size} keywords")

        // Step 4: Import domains
        var domainsImported = 0
        var domainsSkipped = 0
        var parseErrors = 0

        for (entry in siteEntries) {
            val normalized = DomainPolicy.normalize(entry)
            if (normalized == null) {
                Log.w(TAG, "Invalid domain entry: '$entry' — skipping")
                parseErrors++
                continue
            }

            if (normalized in existingDomains) {
                domainsSkipped++
                continue
            }

            val policy = DomainPolicy(
                domain = normalized,
                enabled = true,
            )
            val success = domainRepository.saveDomain(policy)
            if (success) {
                domainsImported++
                existingDomains.add(normalized)
            } else {
                Log.w(TAG, "Failed to save domain: $normalized")
                parseErrors++
            }
        }

        Log.i(TAG, "Domain import: $domainsImported imported, $domainsSkipped skipped, $parseErrors errors")

        // Step 5: Import keywords
        var keywordsImported = 0
        var keywordsSkipped = 0

        for (entry in keywordEntries) {
            val normalized = ContentFilterRule.normalizePhrase(entry)
            if (normalized == null) {
                Log.w(TAG, "Invalid keyword entry: '$entry' — skipping")
                parseErrors++
                continue
            }

            if (normalized in existingKeywords) {
                keywordsSkipped++
                continue
            }

            val rule = ContentFilterRule(
                phrase = normalized,
                enabled = true,
                matchMode = ContentFilterMatchMode.CONTAINS,
            )
            val id = contentFilterRepository.saveRule(rule)
            if (id > 0) {
                keywordsImported++
                existingKeywords.add(normalized)
            } else {
                Log.w(TAG, "Failed to save keyword: $normalized")
                parseErrors++
            }
        }

        Log.i(TAG, "Keyword import: $keywordsImported imported, $keywordsSkipped skipped")

        val result = ImportResult(
            domainsImported = domainsImported,
            domainsSkipped = domainsSkipped,
            keywordsImported = keywordsImported,
            keywordsSkipped = keywordsSkipped,
            parseErrors = parseErrors,
            totalDomainsParsed = siteEntries.size,
            totalKeywordsParsed = keywordEntries.size,
        )

        Log.i(TAG, "Import complete: $result")
        return result
    }

    /**
     * Read the asset file and return its lines, or null if it can't be read.
     */
    private fun readAssetFile(): List<String>? {
        val ctx = context ?: return null
        return try {
            ctx.assets.open(ASSET_PATH)
                .bufferedReader()
                .use { reader ->
                    reader.readLines()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read assets/$ASSET_PATH: ${e.message}")
            null
        }
    }

    /**
     * Test-friendly import that uses lambda callbacks instead of real repositories.
     *
     * This allows unit tests to verify import logic without Android Context,
     * SQLite, or real repository instances.
     *
     * @param siteEntries Parsed site URLs (already extracted from the file)
     * @param keywordEntries Parsed keyword phrases (already extracted from the file)
     * @param existingDomains Set of domains already in the database (mutable — updated in-place)
     * @param existingKeywords Set of keywords already in the database (mutable — updated in-place)
     * @param domainSaver Callback that saves a normalized domain; returns true on success
     * @param keywordSaver Callback that saves a normalized keyword; returns > 0 on success
     */
    internal fun importWithFakes(
        siteEntries: List<String>,
        keywordEntries: List<String>,
        existingDomains: MutableSet<String>,
        existingKeywords: MutableSet<String>,
        domainSaver: (String) -> Boolean,
        keywordSaver: (String) -> Long,
    ): ImportResult? {
        var domainsImported = 0
        var domainsSkipped = 0
        var keywordsImported = 0
        var keywordsSkipped = 0
        var parseErrors = 0

        for (entry in siteEntries) {
            val normalized = DomainPolicy.normalize(entry)
            if (normalized == null) {
                parseErrors++
                continue
            }
            if (normalized in existingDomains) {
                domainsSkipped++
                continue
            }
            val success = domainSaver(normalized)
            if (success) {
                domainsImported++
                existingDomains.add(normalized)
            }
        }

        for (entry in keywordEntries) {
            val normalized = ContentFilterRule.normalizePhrase(entry)
            if (normalized == null) {
                parseErrors++
                continue
            }
            if (normalized in existingKeywords) {
                keywordsSkipped++
                continue
            }
            val id = keywordSaver(normalized)
            if (id > 0) {
                keywordsImported++
                existingKeywords.add(normalized)
            }
        }

        return ImportResult(
            domainsImported = domainsImported,
            domainsSkipped = domainsSkipped,
            keywordsImported = keywordsImported,
            keywordsSkipped = keywordsSkipped,
            parseErrors = parseErrors,
            totalDomainsParsed = siteEntries.size,
            totalKeywordsParsed = keywordEntries.size,
        )
    }

    /**
     * Parse the raw lines into two lists: site URLs and keyword phrases.
     *
     * The parser is stateful — it tracks which section we're in based on
     * the heading markers. Lines before any heading are ignored.
     */
    internal fun parseSections(lines: List<String>): Pair<List<String>, List<String>> {
        val sites = mutableListOf<String>()
        val keywords = mutableListOf<String>()

        var currentSection: Section? = null

        for (rawLine in lines) {
            val line = rawLine.trim()

            // Skip blank lines
            if (line.isBlank()) continue

            // Skip comment lines
            if (line.startsWith("#")) continue

            // Check for section headings
            val lowerLine = line.lowercase()
            if (lowerLine == SECTION_SITES) {
                currentSection = Section.SITES
                continue
            }
            if (lowerLine == SECTION_KEYWORDS) {
                currentSection = Section.KEYWORDS
                continue
            }

            // Skip known placeholder text
            if (lowerLine in PLACEHOLDERS) continue

            // Extract the actual entry, stripping inline comments
            val entry = extractEntry(line)
            if (entry.isBlank()) continue

            // Route to the current section
            when (currentSection) {
                Section.SITES -> sites.add(entry)
                Section.KEYWORDS -> keywords.add(entry)
                null -> {
                    // Line before any section heading — ignore
                    Log.d(TAG, "Ignoring line before section heading: '$line'")
                }
            }
        }

        return Pair(sites, keywords)
    }

    /**
     * Extract a clean entry from a raw line.
     *
     * Handles:
     * - Inline parenthetical comments: `https://example.com (needs account)` → `https://example.com`
     * - Leading/trailing whitespace (already trimmed by caller)
     */
    private fun extractEntry(line: String): String {
        // Strip inline parenthetical comments
        val parenIndex = line.indexOf('(')
        val cleaned = if (parenIndex >= 0) {
            line.substring(0, parenIndex).trim()
        } else {
            line.trim()
        }
        return cleaned
    }

    private enum class Section {
        SITES,
        KEYWORDS,
    }
}
