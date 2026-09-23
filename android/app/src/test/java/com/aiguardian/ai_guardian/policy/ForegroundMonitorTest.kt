package com.aiguardian.ai_guardian.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for [ForegroundMonitor].
 *
 * Tests real-time boundary enforcement for schedule and daily limit.
 * Uses in-memory policy engine (no database needed).
 */
class ForegroundMonitorTest {

    private lateinit var engine: PolicyEngine
    private lateinit var monitor: ForegroundMonitor
    private var enforcedPackage: String? = null
    private var enforcedResult: PolicyResult? = null

    @Before
    fun setUp() {
        engine = PolicyEngine()
        enforcedPackage = null
        enforcedResult = null
        monitor = ForegroundMonitor(engine, null, object : ForegroundMonitor.EnforcementCallback {
            override fun onEnforce(packageName: String, result: PolicyResult) {
                enforcedPackage = packageName
                enforcedResult = result
            }
        })
    }

    // -------------------------------------------------------------------------
    // Basic behavior
    // -------------------------------------------------------------------------

    @Test
    fun `unknown package returns ALLOW and no monitoring`() {
        val result = monitor.onForegroundAppChanged("com.unknown.app")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertNull(monitor.getMonitoredPackage())
    }

    @Test
    fun `BLOCK policy returns BLOCK immediately`() {
        engine.setPolicy(Policy(packageName = "com.block.test", action = PolicyAction.BLOCK))
        val result = monitor.onForegroundAppChanged("com.block.test")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertNull(monitor.getMonitoredPackage())
    }

    @Test
    fun `ALLOW policy with no restrictions returns ALLOW`() {
        engine.setPolicy(Policy(packageName = "com.allow.test", action = PolicyAction.ALLOW))
        val result = monitor.onForegroundAppChanged("com.allow.test")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertNull(monitor.getMonitoredPackage())
    }

    // -------------------------------------------------------------------------
    // Schedule boundary calculation
    // -------------------------------------------------------------------------

    @Test
    fun `schedule start boundary calculated correctly`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = currentMinutes + 5 // 5 minutes from now
        val endMinutes = startMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.schedule.start",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        monitor.onForegroundAppChanged("com.schedule.start")
        assertEquals("com.schedule.start", monitor.getMonitoredPackage())
    }

    @Test
    fun `schedule end boundary calculated when within window`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = maxOf(0, currentMinutes - 30)
        val endMinutes = currentMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.schedule.end",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        val result = monitor.onForegroundAppChanged("com.schedule.end")
        // Currently within window → should BLOCK immediately (schedule active)
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("schedule active", result.reason)
    }

    @Test
    fun `disabled schedule does not trigger monitoring`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = currentMinutes + 5
        val endMinutes = startMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.schedule.disabled",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes, enabled = false)),
        ))

        monitor.onForegroundAppChanged("com.schedule.disabled")
        assertNull(monitor.getMonitoredPackage())
    }

    // -------------------------------------------------------------------------
    // Daily limit boundary calculation
    // -------------------------------------------------------------------------

    @Test
    fun `daily limit boundary calculated when under limit`() {
        engine.usageTracker = MockUsageTracker(usageMs = 0L)
        engine.setPolicy(Policy(
            packageName = "com.limit.under",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 5),
            dailyLimitEnabled = true,
        ))

        monitor.onForegroundAppChanged("com.limit.under")
        assertEquals("com.limit.under", monitor.getMonitoredPackage())
    }

    @Test
    fun `daily limit exceeded blocks immediately`() {
        engine.usageTracker = MockUsageTracker(usageMs = 10 * 60 * 1000L)
        engine.setPolicy(Policy(
            packageName = "com.limit.exceeded",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 5),
            dailyLimitEnabled = true,
        ))

        val result = monitor.onForegroundAppChanged("com.limit.exceeded")
        assertEquals(PolicyAction.BLOCK, result.action)
        assertEquals("daily limit exceeded", result.reason)
    }

    @Test
    fun `disabled daily limit does not trigger monitoring`() {
        engine.setPolicy(Policy(
            packageName = "com.limit.disabled",
            action = PolicyAction.ALLOW,
            dailyLimit = DailyLimit(limitMinutes = 5),
            dailyLimitEnabled = false,
        ))

        monitor.onForegroundAppChanged("com.limit.disabled")
        assertNull(monitor.getMonitoredPackage())
    }

    // -------------------------------------------------------------------------
    // Policy change notifications
    // -------------------------------------------------------------------------

    @Test
    fun `policy change on monitored package recalculates boundary`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = currentMinutes + 5
        val endMinutes = startMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.change.test",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        monitor.onForegroundAppChanged("com.change.test")
        assertEquals("com.change.test", monitor.getMonitoredPackage())

        // Update policy to BLOCK
        engine.setPolicy(Policy(
            packageName = "com.change.test",
            action = PolicyAction.BLOCK,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        monitor.onPolicyChanged("com.change.test")
        // Should have triggered enforcement
        assertEquals("com.change.test", enforcedPackage)
        assertEquals(PolicyAction.BLOCK, enforcedResult?.action)
    }

    @Test
    fun `policy change on non-monitored package is ignored`() {
        engine.setPolicy(Policy(
            packageName = "com.ignore.change",
            action = PolicyAction.ALLOW,
        ))

        monitor.onForegroundAppChanged("com.other.app")
        monitor.onPolicyChanged("com.ignore.change")
        // No enforcement should have been triggered
        assertNull(enforcedPackage)
    }

    @Test
    fun `policy removal stops monitoring`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = currentMinutes + 5
        val endMinutes = startMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.remove.test",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        monitor.onForegroundAppChanged("com.remove.test")
        assertEquals("com.remove.test", monitor.getMonitoredPackage())

        // Remove policy
        engine.removePolicy("com.remove.test")
        monitor.onPolicyChanged("com.remove.test")
        assertNull(monitor.getMonitoredPackage())
    }

    @Test
    fun `cancelAll stops all monitoring`() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = currentMinutes + 5
        val endMinutes = startMinutes + 30

        engine.setPolicy(Policy(
            packageName = "com.cancel.test",
            action = PolicyAction.ALLOW,
            schedules = listOf(RestrictionSchedule(startMinutes = startMinutes, endMinutes = endMinutes)),
        ))

        monitor.onForegroundAppChanged("com.cancel.test")
        assertEquals("com.cancel.test", monitor.getMonitoredPackage())

        monitor.cancelAll()
        assertNull(monitor.getMonitoredPackage())
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian package always returns ALLOW`() {
        val result = monitor.onForegroundAppChanged("com.aiguardian.ai_guardian")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertFalse(result.matched)
        assertNull(monitor.getMonitoredPackage())
    }

    @Test
    fun `empty package returns ALLOW`() {
        val result = monitor.onForegroundAppChanged("")
        assertEquals(PolicyAction.ALLOW, result.action)
        assertNull(monitor.getMonitoredPackage())
    }

    // -------------------------------------------------------------------------
    // Mock UsageTracker
    // -------------------------------------------------------------------------

    private class MockUsageTracker(private val usageMs: Long) : UsageTracker(null) {
        override fun getUsageTodayMs(packageName: String): Long = usageMs
        override fun hasExceededLimit(packageName: String, limitMinutes: Int): Boolean {
            return usageMs >= limitMinutes.toLong() * 60 * 1000
        }
    }
}
