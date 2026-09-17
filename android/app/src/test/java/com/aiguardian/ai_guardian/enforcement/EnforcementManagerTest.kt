package com.aiguardian.ai_guardian.enforcement

import com.aiguardian.ai_guardian.policy.PolicyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EnforcementManager].
 *
 * Tests the deterministic enforcement logic:
 * 1. ALLOW action never triggers enforcement
 * 2. BLOCK action triggers enforcement (first time)
 * 3. AI Guardian package is never blocked
 * 4. Cooldown prevents repeated interventions
 * 5. Different packages trigger new interventions
 * 6. Null/empty package never triggers enforcement
 */
class EnforcementManagerTest {

    private lateinit var manager: EnforcementManager

    @Before
    fun setUp() {
        manager = EnforcementManager()
    }

    // -------------------------------------------------------------------------
    // ALLOW behavior
    // -------------------------------------------------------------------------

    @Test
    fun `ALLOW action never triggers enforcement`() {
        val result = manager.shouldEnforce("com.example.app", PolicyAction.ALLOW)
        assertFalse(result)
    }

    @Test
    fun `ALLOW action does not update intervention state`() {
        manager.shouldEnforce("com.example.app", PolicyAction.ALLOW)
        val state = manager.getEnforcementState()
        assertFalse(state.isActive)
        assertEquals(null, state.lastBlockedPackage)
    }

    // -------------------------------------------------------------------------
    // BLOCK behavior
    // -------------------------------------------------------------------------

    @Test
    fun `BLOCK action triggers enforcement on first detection`() {
        val result = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertTrue(result)
    }

    @Test
    fun `BLOCK action updates enforcement state`() {
        manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        val state = manager.getEnforcementState()
        assertTrue(state.isActive)
        assertEquals("com.example.blocked", state.lastBlockedPackage)
    }

    @Test
    fun `multiple different BLOCK packages trigger separate enforcements`() {
        val result1 = manager.shouldEnforce("com.app.one", PolicyAction.BLOCK)
        assertTrue(result1)

        // Sleep to allow cooldown to pass
        Thread.sleep(1600)

        val result2 = manager.shouldEnforce("com.app.two", PolicyAction.BLOCK)
        assertTrue(result2)

        val state = manager.getEnforcementState()
        assertEquals("com.app.two", state.lastBlockedPackage)
    }

    // -------------------------------------------------------------------------
    // Cooldown behavior
    // -------------------------------------------------------------------------

    @Test
    fun `repeated BLOCK within cooldown period does not trigger enforcement`() {
        val result1 = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertTrue(result1)

        // Immediate second call should be blocked by cooldown
        val result2 = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertFalse(result2)
    }

    @Test
    fun `repeated BLOCK after cooldown expires triggers enforcement again`() {
        val result1 = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertTrue(result1)

        // Sleep for cooldown duration (1500ms) + buffer
        Thread.sleep(1600)

        val result2 = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertTrue(result2)
    }

    // -------------------------------------------------------------------------
    // AI Guardian self-protection
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian package is never blocked`() {
        val result = manager.shouldEnforce("com.aiguardian.ai_guardian", PolicyAction.BLOCK)
        assertFalse(result)
    }

    @Test
    fun `AI Guardian BLOCK does not update enforcement state`() {
        manager.shouldEnforce("com.aiguardian.ai_guardian", PolicyAction.BLOCK)
        val state = manager.getEnforcementState()
        assertFalse(state.isActive)
        assertEquals(null, state.lastBlockedPackage)
    }

    // -------------------------------------------------------------------------
    // Null/empty package behavior
    // -------------------------------------------------------------------------

    @Test
    fun `null package never triggers enforcement`() {
        val result = manager.shouldEnforce(null, PolicyAction.BLOCK)
        assertFalse(result)
    }

    @Test
    fun `empty package never triggers enforcement`() {
        val result = manager.shouldEnforce("", PolicyAction.BLOCK)
        assertFalse(result)
    }

    @Test
    fun `blank package never triggers enforcement`() {
        val result = manager.shouldEnforce("   ", PolicyAction.BLOCK)
        assertFalse(result)
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    @Test
    fun `getLastBlockedPackage returns correct package`() {
        manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertEquals("com.example.blocked", manager.getLastBlockedPackage())
    }

    @Test
    fun `getLastBlockedPackage is null when no enforcement triggered`() {
        assertEquals(null, manager.getLastBlockedPackage())
    }

    @Test
    fun `getEnforcementState reports correct timeSinceLastIntervention`() {
        manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        Thread.sleep(100)
        val state = manager.getEnforcementState()
        assertTrue(state.timeSinceLastIntervention >= 100)
    }

    // -------------------------------------------------------------------------
    // Reset behavior
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears enforcement state`() {
        manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertEquals("com.example.blocked", manager.getLastBlockedPackage())

        manager.reset()
        assertEquals(null, manager.getLastBlockedPackage())
        val state = manager.getEnforcementState()
        assertFalse(state.isActive)
    }

    @Test
    fun `reset allows same package to be enforced again immediately`() {
        manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        manager.reset()

        // Should trigger enforcement immediately without cooldown
        val result = manager.shouldEnforce("com.example.blocked", PolicyAction.BLOCK)
        assertTrue(result)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `very long package name can trigger enforcement`() {
        val longPackage = "com.${"a".repeat(200)}.app"
        val result = manager.shouldEnforce(longPackage, PolicyAction.BLOCK)
        assertTrue(result)
    }

    @Test
    fun `package with special characters can trigger enforcement`() {
        val result = manager.shouldEnforce("com.example.app_123", PolicyAction.BLOCK)
        assertTrue(result)
    }

    @Test
    fun `enforcement state is thread-safe`() {
        val threads = (1..10).map { i ->
            Thread {
                manager.shouldEnforce("com.example.app$i", PolicyAction.BLOCK)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should have enforced for at least one package
        assertTrue(manager.getEnforcementState().isActive)
    }
}
