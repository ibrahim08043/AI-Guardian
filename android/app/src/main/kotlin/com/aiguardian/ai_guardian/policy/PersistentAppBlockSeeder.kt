package com.aiguardian.ai_guardian.policy

import android.util.Log

/**
 * Idempotent seeder for persistent package-based app block rules.
 *
 * Creates (or restores) always-on BLOCK policies for a small fixed set of
 * packages. Rules are keyed by Android package name only — they do NOT
 * require the target app to be installed at rule-creation time.
 *
 * Architecture:
 * - Persistent rule stored in SQLite `policies` table via [PolicyRepository]
 * - Foreground package detected by AccessibilityService
 * - [PolicyEngine.evaluate(packageName)] returns BLOCK
 * - Existing EnforcementManager → BlockActivity path is used unchanged
 *
 * Uninstall/reinstall of the target app does NOT delete these rules, because
 * nothing in the package-removal path removes policy rows by package name.
 *
 * ## Idempotency
 * Running this seeder multiple times is safe:
 * - Existing enabled BLOCK policies are left untouched (no duplicates)
 * - Missing policies are created
 * - Disabled or non-BLOCK policies for these packages are restored to
 *   enabled BLOCK (these packages are intentionally always blocked)
 *
 * ## Scope
 * Only packages listed in [REQUIRED_BLOCK_PACKAGES] are touched.
 * No other policies, domains, or content-filter rules are modified.
 */
object PersistentAppBlockSeeder {

    private const val TAG = "AIGuardianPersistentBlock"

    /**
     * Official Facebook Android application package identifier
     * (Google Play package for the main Facebook app).
     */
    const val FACEBOOK_PACKAGE = "com.facebook.katana"

    /**
     * Official Reddit Android application package identifier
     * (Google Play package for the main Reddit app).
     */
    const val REDDIT_PACKAGE = "com.reddit.frontpage"

    /** Packages that must always have an enabled BLOCK policy. */
    val REQUIRED_BLOCK_PACKAGES: List<String> = listOf(
        FACEBOOK_PACKAGE,
        REDDIT_PACKAGE,
    )

    /** Summary of a seeding run — useful for logs and unit tests. */
    data class SeedResult(
        val created: List<String>,
        val updated: List<String>,
        val alreadyPresent: List<String>,
    ) {
        val totalTouched: Int get() = created.size + updated.size
    }

    /**
     * Ensure persistent BLOCK policies exist for [REQUIRED_BLOCK_PACKAGES].
     *
     * Uses the existing [PolicyEngine] path:
     * - With a repository: policies are written to SQLite and cached
     * - Without a repository (unit tests): policies live in engine memory
     *
     * Never deletes other policies. Never touches website/domain rules.
     * Never touches content-filter rules.
     */
    fun ensurePersistentBlocks(engine: PolicyEngine): SeedResult {
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val alreadyPresent = mutableListOf<String>()

        val existingByPackage = try {
            engine.getPolicies().associateBy { it.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load existing policies for seeding: ${e.message}")
            emptyMap()
        }

        for (packageName in REQUIRED_BLOCK_PACKAGES) {
            val current = existingByPackage[packageName]

            // Already the desired state — skip to avoid duplicate writes.
            if (current != null && current.action == PolicyAction.BLOCK && current.enabled) {
                alreadyPresent.add(packageName)
                Log.d(TAG, "Persistent block already present for $packageName")
                continue
            }

            // Create or restore enabled BLOCK for this package only.
            val policy = Policy(
                packageName = packageName,
                action = PolicyAction.BLOCK,
                enabled = true,
            )
            engine.setPolicy(policy)

            if (current == null) {
                created.add(packageName)
                Log.i(TAG, "Created persistent BLOCK rule for $packageName (app may not be installed)")
            } else {
                updated.add(packageName)
                Log.i(
                    TAG,
                    "Restored persistent BLOCK rule for $packageName " +
                        "(was action=${current.action}, enabled=${current.enabled})"
                )
            }
        }

        // Reload cache from repository when available so evaluate() sees the rules.
        try {
            engine.refreshCache()
        } catch (e: Exception) {
            Log.w(TAG, "refreshCache after seeding failed: ${e.message}")
        }

        val result = SeedResult(created = created, updated = updated, alreadyPresent = alreadyPresent)
        Log.i(
            TAG,
            "Persistent app block seed complete: " +
                "created=${result.created} updated=${result.updated} " +
                "alreadyPresent=${result.alreadyPresent}"
        )
        return result
    }
}
