package com.aiguardian.ai_guardian.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PersistentAppBlockSeeder] and the package-based
 * PolicyEngine path used for Facebook/Reddit blocking.
 *
 * These tests intentionally do NOT require the target apps to be installed.
 * Policy rules are keyed by package name only.
 */
class PersistentAppBlockSeederTest {

    private lateinit var engine: PolicyEngine

    @Before
    fun setUp() {
        engine = PolicyEngine() // in-memory — no repository, no installed apps
    }

    private fun policyFor(packageName: String): Policy? =
        engine.getPolicies().find { it.packageName == packageName }

    // -------------------------------------------------------------------------
    // Package identifier documentation
    // -------------------------------------------------------------------------

    @Test
    fun `official package identifiers are stable`() {
        assertEquals("com.facebook.katana", PersistentAppBlockSeeder.FACEBOOK_PACKAGE)
        assertEquals("com.reddit.frontpage", PersistentAppBlockSeeder.REDDIT_PACKAGE)
        assertEquals(
            listOf(
                PersistentAppBlockSeeder.FACEBOOK_PACKAGE,
                PersistentAppBlockSeeder.REDDIT_PACKAGE,
            ),
            PersistentAppBlockSeeder.REQUIRED_BLOCK_PACKAGES,
        )
    }

    // -------------------------------------------------------------------------
    // Test 1 & 2: rules can exist when apps are not installed
    // -------------------------------------------------------------------------

    @Test
    fun `Facebook blocked rule can exist when Facebook is not installed`() {
        // No PackageManager check — rule is package-name based only.
        val result = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        assertTrue(result.created.contains(PersistentAppBlockSeeder.FACEBOOK_PACKAGE))

        val policy = policyFor(PersistentAppBlockSeeder.FACEBOOK_PACKAGE)
        assertTrue(policy != null)
        assertEquals(PolicyAction.BLOCK, policy!!.action)
        assertTrue(policy.enabled)
    }

    @Test
    fun `Reddit blocked rule can exist when Reddit is not installed`() {
        val result = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        assertTrue(result.created.contains(PersistentAppBlockSeeder.REDDIT_PACKAGE))

        val policy = policyFor(PersistentAppBlockSeeder.REDDIT_PACKAGE)
        assertTrue(policy != null)
        assertEquals(PolicyAction.BLOCK, policy!!.action)
        assertTrue(policy.enabled)
    }

    // -------------------------------------------------------------------------
    // Test 3 & 4: PolicyEngine returns BLOCK for those packages
    // -------------------------------------------------------------------------

    @Test
    fun `PolicyEngine returns BLOCK for Facebook package identifier`() {
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        val result = engine.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE)
        assertEquals(PolicyAction.BLOCK, result.action)
        assertTrue(result.matched)
        assertEquals(PersistentAppBlockSeeder.FACEBOOK_PACKAGE, result.packageName)
    }

    @Test
    fun `PolicyEngine returns BLOCK for Reddit package identifier`() {
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        val result = engine.evaluate(PersistentAppBlockSeeder.REDDIT_PACKAGE)
        assertEquals(PolicyAction.BLOCK, result.action)
        assertTrue(result.matched)
        assertEquals(PersistentAppBlockSeeder.REDDIT_PACKAGE, result.packageName)
    }

    // -------------------------------------------------------------------------
    // Test 5: rules survive storage reload
    // -------------------------------------------------------------------------

    @Test
    fun `rules survive storage reload into a fresh engine`() {
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        // Simulate process restart: copy persisted policies into a new engine.
        val restored = PolicyEngine()
        for (policy in engine.getPolicies()) {
            restored.setPolicy(policy)
        }
        restored.refreshCache()

        assertEquals(
            PolicyAction.BLOCK,
            restored.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE).action,
        )
        assertEquals(
            PolicyAction.BLOCK,
            restored.evaluate(PersistentAppBlockSeeder.REDDIT_PACKAGE).action,
        )
    }

    // -------------------------------------------------------------------------
    // Test 6: initialization is idempotent
    // -------------------------------------------------------------------------

    @Test
    fun `running initialization multiple times does not create duplicate rules`() {
        val first = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        assertEquals(2, first.created.size)
        assertTrue(first.updated.isEmpty())

        val second = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        assertTrue(second.created.isEmpty())
        assertTrue(second.updated.isEmpty())
        assertEquals(2, second.alreadyPresent.size)

        val third = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        assertTrue(third.created.isEmpty())
        assertTrue(third.updated.isEmpty())

        // Exactly one policy row per package in the engine map.
        val fbCount = engine.getPolicies().count { it.packageName == PersistentAppBlockSeeder.FACEBOOK_PACKAGE }
        val redditCount = engine.getPolicies().count { it.packageName == PersistentAppBlockSeeder.REDDIT_PACKAGE }
        assertEquals(1, fbCount)
        assertEquals(1, redditCount)
        assertEquals(2, engine.getPolicies().size)
    }

    // -------------------------------------------------------------------------
    // Test 7: existing app-block rules still work
    // -------------------------------------------------------------------------

    @Test
    fun `existing unrelated app-block rules still work after seeding`() {
        engine.setPolicy(Policy(packageName = "com.existing.blocked.app", action = PolicyAction.BLOCK, enabled = true))
        engine.setPolicy(Policy(packageName = "com.existing.allowed.app", action = PolicyAction.ALLOW, enabled = true))

        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.existing.blocked.app").action)
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.existing.allowed.app").action)
        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE).action)
        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.REDDIT_PACKAGE).action)
    }

    @Test
    fun `disabled Facebook policy is restored to enabled BLOCK`() {
        engine.setPolicy(
            Policy(
                packageName = PersistentAppBlockSeeder.FACEBOOK_PACKAGE,
                action = PolicyAction.BLOCK,
                enabled = false,
            )
        )

        val result = PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        assertTrue(result.updated.contains(PersistentAppBlockSeeder.FACEBOOK_PACKAGE))

        val policy = policyFor(PersistentAppBlockSeeder.FACEBOOK_PACKAGE)
        assertTrue(policy != null)
        assertEquals(PolicyAction.BLOCK, policy!!.action)
        assertTrue(policy.enabled)
        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE).action)
    }

    @Test
    fun `ALLOW policy for Facebook is converted to BLOCK`() {
        engine.setPolicy(
            Policy(
                packageName = PersistentAppBlockSeeder.FACEBOOK_PACKAGE,
                action = PolicyAction.ALLOW,
                enabled = true,
            )
        )

        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE).action)
    }

    // -------------------------------------------------------------------------
    // Test 8: unrelated packages are NOT blocked by the new rules
    // -------------------------------------------------------------------------

    @Test
    fun `unrelated package is NOT blocked by Facebook Reddit rules`() {
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        val unrelated = listOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.snapchat.android",
            "com.whatsapp",
            "com.android.chrome",
            "com.android.vending",
            "com.unknown.app",
            "com.reddit.frontpage.beta", // similar but not exact package id
            "com.facebook.katana.lite",  // similar but not exact package id
        )

        for (pkg in unrelated) {
            val result = engine.evaluate(pkg)
            assertEquals("Package $pkg must remain ALLOW", PolicyAction.ALLOW, result.action)
            assertFalse("Package $pkg must not match a policy", result.matched)
        }
    }

    // -------------------------------------------------------------------------
    // Future-installation scenario (most important)
    // -------------------------------------------------------------------------

    @Test
    fun `package not installed when rule created is blocked after later appearance`() {
        // 1. Rule created while app is absent (no install check performed).
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        // 2. App is "installed later" — only its package name becomes foreground.
        //    PolicyEngine does not consult PackageManager; it only checks rules.
        val foregroundWhenFacebookInstalled = PersistentAppBlockSeeder.FACEBOOK_PACKAGE
        val facebookDecision = engine.evaluate(foregroundWhenFacebookInstalled)
        assertEquals(PolicyAction.BLOCK, facebookDecision.action)
        assertTrue(facebookDecision.matched)

        val foregroundWhenRedditInstalled = PersistentAppBlockSeeder.REDDIT_PACKAGE
        val redditDecision = engine.evaluate(foregroundWhenRedditInstalled)
        assertEquals(PolicyAction.BLOCK, redditDecision.action)
        assertTrue(redditDecision.matched)
    }

    @Test
    fun `uninstall reinstall cycle does not remove the rule`() {
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        // Uninstall: package disappears from device; policy row remains.
        // (No code path deletes policy rows on PACKAGE_REMOVED.)
        // Reinstall: package name is identical; rule still applies.
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine) // app restart / service restart

        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.FACEBOOK_PACKAGE).action)
        assertEquals(PolicyAction.BLOCK, engine.evaluate(PersistentAppBlockSeeder.REDDIT_PACKAGE).action)
    }

    // -------------------------------------------------------------------------
    // Content Filtering / domain filtering isolation
    // -------------------------------------------------------------------------

    @Test
    fun `seeder does not create domain or content-filter side effects`() {
        // PolicyEngine only manages app policies. Seeder only calls setPolicy
        // for the two required packages — no DomainRepository / ContentFilter calls.
        PersistentAppBlockSeeder.ensurePersistentBlocks(engine)

        val packages = engine.getPolicies().map { it.packageName }.toSet()
        assertEquals(
            setOf(
                PersistentAppBlockSeeder.FACEBOOK_PACKAGE,
                PersistentAppBlockSeeder.REDDIT_PACKAGE,
            ),
            packages,
        )
    }
}
