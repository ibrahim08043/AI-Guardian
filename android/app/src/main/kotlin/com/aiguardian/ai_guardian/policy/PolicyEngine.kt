package com.aiguardian.ai_guardian.policy

import android.util.Log
import com.aiguardian.ai_guardian.storage.PolicyRepository

/**
 * Deterministic local Policy Engine with smart restriction rules.
 *
 * Evaluates a package name against a set of rules and returns
 * an allow/block decision. This engine:
 * - Loads policies from persistent storage
 * - Evaluates schedule restrictions (time-based blocking)
 * - Evaluates daily usage limits
 * - Is deterministic (same input → same output)
 * - Uses fail-safe defaults (unknown → ALLOW)
 * - Fails safely if database is unavailable (→ ALLOW)
 * - Never crashes on malformed input
 *
 * CRITICAL: AI Guardian itself is ALWAYS allowed.
 *
 * ## Deterministic Rule Precedence
 *
 * When evaluating a package, the following precedence applies
 * (checked in order — first match wins):
 *
 * 1. Null/blank package → ALLOW (fail-safe)
 * 2. AI Guardian package → ALLOW (self-protection)
 * 3. Repository unavailable → ALLOW (fail-safe)
 * 4. No matching policy found → ALLOW (fail-safe)
 * 5. Policy disabled (`enabled=false`) → ALLOW
 * 6. Schedule enabled AND currently within schedule window → BLOCK
 *    - If base action is ALLOW, schedule still forces BLOCK during window
 *    - Schedule takes precedence over base action during active window
 * 7. Daily limit enabled AND usage exceeded → BLOCK
 *    - If base action is ALLOW, daily limit still forces BLOCK when exceeded
 *    - Daily limit takes precedence over base action when exceeded
 * 8. Database error during evaluation → ALLOW (fail-safe)
 * 9. Otherwise → return policy's base action (ALLOW or BLOCK)
 *
 * ## Fail-Safe Guarantees
 *
 * Every possible failure path results in ALLOW:
 * - Database read fails → ALLOW
 * - Malformed policy → treated as not found → ALLOW
 * - Invalid schedule → schedule ignored → evaluate base action
 * - Usage data unavailable → limit not enforced → evaluate base action
 * - Unexpected exception → ALLOW
 */
class PolicyEngine(private val repository: PolicyRepository? = null) {

    companion object {
        private const val TAG = "AIGuardianPolicy"
        private const val OWN_PACKAGE = "com.aiguardian.ai_guardian"
    }

    /** Optional UsageTracker for daily limit evaluation. */
    var usageTracker: UsageTracker? = null

    /**
     * In-memory policy map used as fallback when no repository is available.
     * Also used in unit tests where database is not needed.
     * Production code always uses the repository.
     */
    private val inMemoryPolicies = mutableMapOf<String, Policy>()

    /**
     * Evaluate a package name against all active policies.
     *
     * Rules (in order):
     * 1. Null/blank package → ALLOW (fail-safe)
     * 2. AI Guardian package → ALWAYS ALLOW (self-protection)
     * 3. Repository unavailable → ALLOW (fail-safe)
     * 4. No matching enabled policy → ALLOW (fail-safe)
     * 5. Matching disabled policy → ALLOW
     * 6. Schedule enabled & within window → BLOCK
     * 7. Daily limit enabled & exceeded → BLOCK
     * 8. Otherwise → return policy's base action
     *
     * This method never throws. It always returns a valid [PolicyResult].
     */
    fun evaluate(packageName: String?): PolicyResult {
        // Rule 1: Null/blank → ALLOW (fail-safe)
        if (packageName.isNullOrBlank()) {
            val result = PolicyResult.allow(packageName.orEmpty(), "empty input")
            Log.d(TAG, "package=${result.packageName} action=${result.action} (${result.reason})")
            return result
        }

        // Rule 2: Self-protection — AI Guardian always allowed
        if (packageName == OWN_PACKAGE) {
            val result = PolicyResult.allow(packageName, "self-protection")
            Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
            return result
        }

        // Rule 3: Look up policy (repository first, then in-memory fallback)
        val policy = try {
            if (repository != null) {
                repository.getPolicy(packageName)
            } else {
                inMemoryPolicies[packageName]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database error evaluating $packageName: ${e.message}, defaulting to ALLOW")
            return PolicyResult.allow(packageName, "database error")
        }

        // Rule 4: No policy found → ALLOW
        if (policy == null) {
            val result = PolicyResult.allow(packageName, "no policy")
            Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
            return result
        }

        // Rule 5: Policy disabled → ALLOW
        if (!policy.enabled) {
            val result = PolicyResult.allow(packageName, "policy disabled")
            Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
            return result
        }

        // Rule 6: Schedule check — if enabled and within window → BLOCK
        if (policy.scheduleEnabled && policy.schedule != null) {
            try {
                if (ScheduleEvaluator.isWithinSchedule(policy.schedule)) {
                    val result = PolicyResult.block(packageName, "schedule active")
                    Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Schedule evaluation failed for $packageName: ${e.message}, ignoring schedule")
                // Fall through to daily limit / base action check
            }
        }

        // Rule 7: Daily limit check — if enabled and exceeded → BLOCK
        if (policy.dailyLimitEnabled && policy.dailyLimit != null) {
            try {
                val tracker = usageTracker
                if (tracker != null) {
                    if (tracker.hasExceededLimit(packageName, policy.dailyLimit.limitMinutes)) {
                        val result = PolicyResult.block(packageName, "daily limit exceeded")
                        Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
                        return result
                    }
                } else {
                    Log.w(TAG, "UsageTracker unavailable, skipping daily limit check for $packageName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Daily limit evaluation failed for $packageName: ${e.message}, ignoring limit")
                // Fall through to base action check
            }
        }

        // Rule 9: Return policy's base action
        val result = PolicyResult(
            packageName = packageName,
            action = policy.action,
            matched = true,
            reason = "policy match",
        )
        Log.d(TAG, "package=$packageName action=${result.action} (${result.reason})")
        return result
    }

    /**
     * Add or update a policy at runtime.
     * Changes are persisted to the database immediately.
     */
    fun setPolicy(policy: Policy) {
        try {
            if (repository != null) {
                repository.savePolicy(policy)
            } else {
                inMemoryPolicies[policy.packageName] = policy
            }
            Log.d(TAG, "Policy saved: ${policy.packageName} → ${policy.action}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save policy: ${e.message}")
        }
    }

    /**
     * Remove a policy for a specific package.
     * Changes are persisted to the database immediately.
     */
    fun removePolicy(packageName: String) {
        try {
            if (repository != null) {
                repository.deletePolicy(packageName)
            } else {
                inMemoryPolicies.remove(packageName)
            }
            Log.d(TAG, "Policy deleted: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete policy: ${e.message}")
        }
    }

    /**
     * Enable or disable a policy.
     * Changes are persisted to the database immediately.
     */
    fun setPolicyEnabled(packageName: String, enabled: Boolean) {
        try {
            if (repository != null) {
                repository.setPolicyEnabled(packageName, enabled)
            } else {
                val policy = inMemoryPolicies[packageName]
                if (policy != null) {
                    inMemoryPolicies[packageName] = policy.copy(enabled = enabled)
                }
            }
            Log.d(TAG, "Policy enabled=$enabled: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update policy: ${e.message}")
        }
    }

    /** Get a snapshot of all current policies (for debug UI). */
    fun getPolicies(): List<Policy> {
        return try {
            if (repository != null) {
                repository.getAllPolicies()
            } else {
                inMemoryPolicies.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve policies: ${e.message}")
            emptyList()
        }
    }
}
