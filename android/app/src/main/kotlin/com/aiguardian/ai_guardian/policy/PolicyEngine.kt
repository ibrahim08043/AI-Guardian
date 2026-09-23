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
     * In-memory cache of policies keyed by package name.
     * Avoids hitting SQLite on every evaluate() call during frequent app switching.
     * Kept in sync by setPolicy(), removePolicy(), setPolicyEnabled(), and refreshCache().
     */
    @Volatile
    private var policyCache: Map<String, Policy> = emptyMap()

    /**
     * Reload the entire policy cache from the repository.
     * Call this on startup or when a bulk policy change occurs.
     */
    fun refreshCache() {
        if (repository != null) {
            try {
                val policies = repository.getAllPolicies()
                policyCache = policies.associateBy { it.packageName }
                Log.i(TAG, "Policy cache refreshed: ${policyCache.size} policies")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh policy cache: ${e.message}")
            }
        }
    }

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
            return PolicyResult.allow(packageName.orEmpty(), "empty input")
        }

        // Rule 2: Self-protection — AI Guardian always allowed
        if (packageName == OWN_PACKAGE) {
            return PolicyResult.allow(packageName, "self-protection")
        }

        // Rule 3: Look up policy (cache first, then repository, then in-memory fallback)
        val policy = try {
            val cached = policyCache[packageName]
            if (cached != null) {
                cached
            } else if (repository != null) {
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
            return PolicyResult.allow(packageName, "no policy")
        }

        // Rule 5: Policy disabled → ALLOW
        if (!policy.enabled) {
            return PolicyResult.allow(packageName, "policy disabled")
        }

        // Rule 6: Schedule check — if any enabled schedule is active → BLOCK
        if (policy.schedules.isNotEmpty()) {
            try {
                if (ScheduleEvaluator.anyActiveSchedule(policy.schedules)) {
                    return PolicyResult.block(packageName, "schedule active")
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
                        return PolicyResult.block(packageName, "daily limit exceeded")
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
        return PolicyResult(
            packageName = packageName,
            action = policy.action,
            matched = true,
            reason = "policy match",
        )
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
            // Update cache
            policyCache = policyCache + (policy.packageName to policy)
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
            // Remove from cache
            policyCache = policyCache - packageName
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
            // Update cache
            val cached = policyCache[packageName]
            if (cached != null) {
                policyCache = policyCache + (packageName to cached.copy(enabled = enabled))
            }
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
