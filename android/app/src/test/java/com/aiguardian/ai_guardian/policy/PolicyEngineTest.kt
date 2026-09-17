package com.aiguardian.ai_guardian.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for [PolicyEngine].
 *
 * Tests the fail-safe evaluation rules with Phase C smart restrictions.
 * Uses in-memory policy storage (no database needed).
 */
class PolicyEngineTest {

    private lateinit var engine: PolicyEngine

    @Before
    fun setUp() {
        engine = PolicyEngine() // No repository — uses in-memory policies
    }

    // -------------------------------------------------------------------------
    // Fail-safe defaults
    // -------------------------------------------------------------------------

    @Test
    fun `null package returns ALLOW`() {
        val result = engine.evaluate(null)
        assertEquals(PolicyAction.ALLOW, result.action)
        assertEquals("", result.packageName)
        assertFalse(result.matched)
    }

    @Test
    fun `empty package returns ALLOW`() {
        val result = engine.evaluate("")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertEquals("", result.packageName)
        assertFalse(result.matched)
    }

    @Test
    fun `blank package returns ALLOW`() {
        val result = engine.evaluate("   ")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertFalse(result.matched)
    }

    @Test
    fun `unknown package returns ALLOW`() {
        val result = engine.evaluate("com.unknown.app")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertEquals("com.unknown.app", result.packageName)
        assertFalse(result.matched)
    }

    // -------------------------------------------------------------------------
    // Self-protection
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian package always returns ALLOW`() {
        val result = engine.evaluate("com.aiguardian.ai_guardian")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertFalse(result.matched)
        assertEquals("self-protection", result.reason)
    }

    // -------------------------------------------------------------------------
    // Basic policy evaluation
    // -------------------------------------------------------------------------

    @Test
    fun `matching BLOCK policy returns BLOCK`() {
        engine.setPolicy(Policy(packageName = "com.test.block", action = PolicyAction.BLOCK))
        val result = engine.evaluate("com.test.block")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertTrue(result.matched)
    }

    @Test
    fun `matching ALLOW policy returns ALLOW`() {
        engine.setPolicy(Policy(packageName = "com.test.allow", action = PolicyAction.ALLOW))
        val result = engine.evaluate("com.test.allow")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertTrue(result.matched)
    }

    // -------------------------------------------------------------------------
    // Disabled policies
    // -------------------------------------------------------------------------

    @Test
    fun `disabled policy returns ALLOW even if package matches`() {
        engine.setPolicy(Policy(
            packageName = "com.test.disabled",
            action = PolicyAction.BLOCK,
            enabled = false,
        ))
        val result = engine.evaluate("com.test.disabled")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertFalse(result.matched)
    }

    // -------------------------------------------------------------------------
    // Multiple policies
    // -------------------------------------------------------------------------

    @Test
    fun `multiple policies - correct package selected`() {
        engine.setPolicy(Policy(packageName = "com.app.one", action = PolicyAction.BLOCK))
        engine.setPolicy(Policy(packageName = "com.app.two", action = PolicyAction.ALLOW))
        engine.setPolicy(Policy(packageName = "com.app.three", action = PolicyAction.BLOCK))

        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.app.one").action)
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.app.two").action)
        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.app.three").action)
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.app.four").action)
    }

    // -------------------------------------------------------------------------
    // setPolicy / removePolicy
    // -------------------------------------------------------------------------

    @Test
    fun `setPolicy adds new policy`() {
        engine.setPolicy(Policy(packageName = "com.new.app", action = PolicyAction.BLOCK))
        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.new.app").action)
    }

    @Test
    fun `setPolicy updates existing policy`() {
        engine.setPolicy(Policy(packageName = "com.update.app", action = PolicyAction.BLOCK))
        engine.setPolicy(Policy(packageName = "com.update.app", action = PolicyAction.ALLOW))
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.update.app").action)
    }

    @Test
    fun `removePolicy removes policy`() {
        engine.setPolicy(Policy(packageName = "com.temp.app", action = PolicyAction.BLOCK))
        engine.removePolicy("com.temp.app")
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.temp.app").action)
    }

    @Test
    fun `removePolicy on non-existent package is safe`() {
        engine.removePolicy("com.nonexistent.app")
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.nonexistent.app").action)
    }

    // -------------------------------------------------------------------------
    // getPolicies
    // -------------------------------------------------------------------------

    @Test
    fun `getPolicies returns all policies`() {
        engine.setPolicy(Policy(packageName = "com.test.one", action = PolicyAction.BLOCK))
        engine.setPolicy(Policy(packageName = "com.test.two", action = PolicyAction.ALLOW))
        assertEquals(2, engine.getPolicies().size)
    }

    // -------------------------------------------------------------------------
    // Phase C: Schedule evaluation
    // -------------------------------------------------------------------------

    @Test
    fun `schedule active at current time blocks app`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = maxOf(0, currentMinutes - 60)
        val endMinutes = minOf(1439, currentMinutes + 60)

        engine.setPolicy(Policy(
            packageName = "com.schedule.active",
            action = PolicyAction.ALLOW,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
        ))

        val result = engine.evaluate("com.schedule.active")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("schedule active", result.reason)
    }

    @Test
    fun `schedule not active at current time uses base action`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val startMinutes: Int
        val endMinutes: Int
        if (currentMinutes in 180..300) {
            startMinutes = 600; endMinutes = 720
        } else {
            startMinutes = 180; endMinutes = 300
        }

        engine.setPolicy(Policy(
            packageName = "com.schedule.inactive",
            action = PolicyAction.BLOCK,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
        ))

        val result = engine.evaluate("com.schedule.inactive")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("policy match", result.reason)
    }

    @Test
    fun `disabled schedule does not affect evaluation`() {
        engine.setPolicy(Policy(
            packageName = "com.schedule.disabled",
            action = PolicyAction.BLOCK,
            schedule = RestrictionSchedule(startMinutes = 0, endMinutes = 1439),
            scheduleEnabled = false,
        ))
        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.schedule.disabled").action)
    }

    // -------------------------------------------------------------------------
    // Phase C: Daily limit evaluation
    // -------------------------------------------------------------------------

    @Test
    fun `daily limit not exceeded returns base action`() {
        engine.setPolicy(Policy(
            packageName = "com.limited.ok",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.limited.ok").action)
    }

    @Test
    fun `daily limit exceeded with mock tracker returns BLOCK`() {
        engine.usageTracker = MockUsageTracker(usageMs = 31 * 60 * 1000L)
        engine.setPolicy(Policy(
            packageName = "com.limited.exceeded",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))
        val result = engine.evaluate("com.limited.exceeded")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("daily limit exceeded", result.reason)
    }

    @Test
    fun `daily limit not exceeded with mock tracker returns base action`() {
        engine.usageTracker = MockUsageTracker(usageMs = 15 * 60 * 1000L)
        engine.setPolicy(Policy(
            packageName = "com.limited.under",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.limited.under").action)
    }

    @Test
    fun `daily limit disabled does not affect evaluation`() {
        engine.setPolicy(Policy(
            packageName = "com.limited.off",
            action = PolicyAction.BLOCK,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = false,
        ))
        assertEquals(PolicyAction.BLOCK, engine.evaluate("com.limited.off").action)
    }

    // -------------------------------------------------------------------------
    // Phase C: Combined schedule + limit
    // -------------------------------------------------------------------------

    @Test
    fun `schedule takes precedence over daily limit when active`() {
        engine.usageTracker = MockUsageTracker(usageMs = 5 * 60 * 1000L)

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = maxOf(0, currentMinutes - 60)
        val endMinutes = minOf(1439, currentMinutes + 60)

        engine.setPolicy(Policy(
            packageName = "com.combined.schedule",
            action = PolicyAction.ALLOW,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))

        val result = engine.evaluate("com.combined.schedule")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("schedule active", result.reason)
    }

    @Test
    fun `daily limit checked when schedule not active`() {
        engine.usageTracker = MockUsageTracker(usageMs = 31 * 60 * 1000L)

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes: Int
        val endMinutes: Int
        if (currentMinutes in 180..300) {
            startMinutes = 600; endMinutes = 720
        } else {
            startMinutes = 180; endMinutes = 300
        }

        engine.setPolicy(Policy(
            packageName = "com.combined.limit",
            action = PolicyAction.ALLOW,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))

        val result = engine.evaluate("com.combined.limit")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("daily limit exceeded", result.reason)
    }

    // -------------------------------------------------------------------------
    // Bug fix: Restrict toggle vs schedule/limit independence
    // -------------------------------------------------------------------------

    /** Scenario (a): Restrict App ON + No Schedule = ALWAYS BLOCKED */
    @Test
    fun `restrict on with no schedule always blocks`() {
        engine.setPolicy(Policy(
            packageName = "com.restrict.only",
            action = PolicyAction.BLOCK,
            enabled = true,
        ))
        val result = engine.evaluate("com.restrict.only")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("policy match", result.reason)
    }

    /** Scenario (b): Restrict App OFF + Schedule ON = BLOCK only during schedule */
    @Test
    fun `restrict off with schedule active blocks only during window`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = maxOf(0, currentMinutes - 60)
        val endMinutes = minOf(1439, currentMinutes + 60)

        // action=ALLOW (restrict off), schedule enabled → schedule forces BLOCK
        engine.setPolicy(Policy(
            packageName = "com.schedule.only",
            action = PolicyAction.ALLOW,
            enabled = true,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
        ))
        val result = engine.evaluate("com.schedule.only")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("schedule active", result.reason)
    }

    /** Scenario (c): Restrict App OFF + Daily Limit ON = BLOCK after limit */
    @Test
    fun `restrict off with daily limit exceeded blocks`() {
        engine.usageTracker = MockUsageTracker(usageMs = 31 * 60 * 1000L)

        // action=ALLOW (restrict off), daily limit enabled → limit forces BLOCK
        engine.setPolicy(Policy(
            packageName = "com.limit.only",
            action = PolicyAction.ALLOW,
            enabled = true,
            dailyLimit = DailyLimit(limitMinutes = 30),
            dailyLimitEnabled = true,
        ))
        val result = engine.evaluate("com.limit.only")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("daily limit exceeded", result.reason)
    }

    /** Scenario (d): Restrict App ON + Schedule ON = ALWAYS BLOCKED */
    @Test
    fun `restrict on with schedule always blocks regardless of window`() {
        // Even outside schedule window, base action=BLOCK means ALWAYS BLOCKED
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes: Int
        val endMinutes: Int
        if (currentMinutes in 180..300) {
            startMinutes = 600; endMinutes = 720
        } else {
            startMinutes = 180; endMinutes = 300
        }

        engine.setPolicy(Policy(
            packageName = "com.block.plus.schedule",
            action = PolicyAction.BLOCK,
            enabled = true,
            schedule = RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes),
            scheduleEnabled = true,
        ))
        val result = engine.evaluate("com.block.plus.schedule")
        assertEquals(PolicyAction.BLOCK, result.action)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `malformed input with special characters returns ALLOW`() {
        assertEquals(PolicyAction.ALLOW, engine.evaluate("""com.example;\ DROP TABLE""").action)
    }

    @Test
    fun `very long package name returns ALLOW`() {
        assertEquals(PolicyAction.ALLOW, engine.evaluate("com.${"a".repeat(500)}.app").action)
    }

    @Test
    fun `Policy data class rejects blank packageName`() {
        try {
            Policy(packageName = "", action = PolicyAction.ALLOW)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("blank") == true)
        }
    }

    @Test
    fun `PolicyResult factory creates allow result`() {
        val result = PolicyResult.allow("test.package")
        assertEquals("test.package", result.packageName)
        assertEquals(PolicyAction.ALLOW, result.action)
        assertFalse(result.matched)
    }

    @Test
    fun `PolicyResult block factory creates block result`() {
        val result = PolicyResult.block("test.package", "test reason")
        assertEquals("test.package", result.packageName)
        assertEquals(PolicyAction.BLOCK, result.action)
        assertTrue(result.matched)
        assertEquals("test reason", result.reason)
    }

    // -------------------------------------------------------------------------
    // Mock UsageTracker for testing
    // -------------------------------------------------------------------------

    private class MockUsageTracker(private val usageMs: Long) : UsageTracker(null) {
        override fun getUsageTodayMs(packageName: String): Long = usageMs
        override fun hasExceededLimit(packageName: String, limitMinutes: Int): Boolean {
            return usageMs >= limitMinutes.toLong() * 60 * 1000
        }
    }
}
