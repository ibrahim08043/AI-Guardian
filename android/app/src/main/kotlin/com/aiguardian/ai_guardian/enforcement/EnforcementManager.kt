package com.aiguardian.ai_guardian.enforcement

import android.util.Log
import com.aiguardian.ai_guardian.policy.PolicyAction

/**
 * Manages deterministic app-level enforcement decisions and intervention state.
 *
 * This class:
 * - Tracks blocked app enforcement state in-memory
 * - Implements cooldown to prevent repeated intervention loops
 * - Logs enforcement actions without exposing sensitive data
 * - Ensures AI Guardian is never blocked
 *
 * Enforcement is purely native — no database, no network, no AI.
 */
class EnforcementManager {

    companion object {
        private const val TAG = "AIGuardianEnforcement"
        private const val INTERVENTION_COOLDOWN_MS = 1500L
        private const val OWN_PACKAGE = "com.aiguardian.ai_guardian"
    }

    // In-memory state tracking
    @Volatile
    private var lastBlockedPackage: String? = null

    @Volatile
    private var lastInterventionTime: Long = 0L

    /**
     * Check if enforcement should be triggered for a blocked app.
     *
     * Returns true only if:
     * 1. The action is BLOCK
     * 2. The package is not AI Guardian's own package
     * 3. The cooldown period has passed since the last intervention
     *
     * This method is deterministic and thread-safe.
     */
    fun shouldEnforce(packageName: String?, action: PolicyAction): Boolean {
        // Only enforce BLOCK actions
        if (action != PolicyAction.BLOCK) return false

        // Null/empty package cannot be blocked
        if (packageName.isNullOrBlank()) return false

        // AI Guardian must never block itself
        if (packageName == OWN_PACKAGE) {
            Log.d(TAG, "Self-protection: refusing to block own package")
            return false
        }

        // Cooldown check — prevent repeated interventions for the same package
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastInterventionTime < INTERVENTION_COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active for $packageName (${now - lastInterventionTime}ms elapsed)")
            return false
        }

        // All checks passed — enforcement should proceed
        lastBlockedPackage = packageName
        lastInterventionTime = now
        Log.d(TAG, "Enforcement triggered for $packageName")
        return true
    }

    /**
     * Get the last package that was blocked by enforcement (for debug UI).
     */
    fun getLastBlockedPackage(): String? = lastBlockedPackage

    /**
     * Get the current enforcement state (for debug UI).
     */
    fun getEnforcementState(): EnforcementState {
        val now = System.currentTimeMillis()
        val timeSinceLastIntervention = if (lastBlockedPackage != null) {
            now - lastInterventionTime
        } else {
            0L
        }

        return EnforcementState(
            isActive = lastBlockedPackage != null,
            lastBlockedPackage = lastBlockedPackage,
            timeSinceLastIntervention = timeSinceLastIntervention,
        )
    }

    /**
     * Reset enforcement state (for testing or cleanup).
     */
    fun reset() {
        lastBlockedPackage = null
        lastInterventionTime = 0L
    }

    /**
     * Reset enforcement state for a specific package.
     * Called when BlockActivity is dismissed so the same app can be
     * immediately re-blocked if it returns to the foreground.
     */
    fun resetForPackage(packageName: String) {
        if (packageName == lastBlockedPackage) {
            lastBlockedPackage = null
            lastInterventionTime = 0L
            Log.d(TAG, "Reset enforcement for $packageName")
        }
    }

    data class EnforcementState(
        val isActive: Boolean,
        val lastBlockedPackage: String?,
        val timeSinceLastIntervention: Long,
    )
}
