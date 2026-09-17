package com.aiguardian.ai_guardian.policy

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aiguardian.ai_guardian.storage.PolicyRepository

/**
 * Monitors the currently foreground restricted app and enforces schedule/limit boundaries.
 *
 * When a restricted app with an active schedule or daily limit is in the foreground,
 * this monitor calculates the NEXT enforcement boundary and schedules a single
 * [Handler.postDelayed] to fire at that exact time.
 *
 * On boundary fire:
 *  - Re-reads the latest policy from the repository
 *  - Re-evaluates time/usage
 *  - Runs [PolicyEngine.evaluate]
 *  - Returns the result via [EnforcementCallback]
 *
 * CRITICAL: Uses [PolicyRepository] directly for policy lookups (not in-memory
 * PolicyEngine) because the AccessibilityService's PolicyEngine may not have
 * the repository attached.
 *
 * Guarantees:
 *  - One pending task at most (for the current foreground package)
 *  - Cancelled when app leaves foreground
 *  - Cancelled and rescheduled on policy change
 *  - No busy-waiting, no polling loop
 *  - No network, no Flutter timers
 */
class ForegroundMonitor(
    private var policyEngine: PolicyEngine,
    private val repository: PolicyRepository?,
    private val callback: EnforcementCallback,
) {

    companion object {
        private const val TAG = "AIGuardianMonitor"
    }

    /**
     * Update the PolicyEngine used for evaluation.
     *
     * This handles the startup-order race condition:
     * - AccessibilityService may start before MainActivity.injectPolicyEngine()
     * - onServiceConnected() captures a fallback empty PolicyEngine
     * - When MainActivity later injects the repository-backed engine,
     *   this method swaps it in so subsequent evaluations use the real engine.
     */
    fun updateEngine(newEngine: PolicyEngine) {
        if (newEngine !== policyEngine) {
            Log.d(TAG, "updateEngine: swapping to new PolicyEngine instance")
            policyEngine = newEngine
        }
    }

    /** Callback interface for enforcement actions. */
    interface EnforcementCallback {
        fun onEnforce(packageName: String, result: PolicyResult)
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The package currently being monitored (null if none). */
    @Volatile
    private var monitoredPackage: String? = null

    /** The currently pending boundary task. */
    private var pendingRunnable: Runnable? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called when a new foreground app is detected.
     * Evaluates policy immediately and schedules boundary monitoring if needed.
     *
     * @param packageName The new foreground package.
     * @return The immediate policy result (for caller to enforce).
     */
    fun onForegroundAppChanged(packageName: String): PolicyResult {
        Log.d(TAG, "═══ onForegroundAppChanged: $packageName ═══")

        // Cancel any previous monitoring
        cancelPending()

        // Evaluate policy immediately using the engine
        val result = policyEngine.evaluate(packageName)
        Log.d(TAG, "  [1] PolicyEngine evaluate: action=${result.action} matched=${result.matched} reason=${result.reason}")

        // If BLOCK right now, return it — no need to schedule
        if (result.action == PolicyAction.BLOCK) {
            Log.d(TAG, "  [2] BLOCK immediately — no monitoring needed")
            monitoredPackage = null
            return result
        }

        // Policy is ALLOW — check if schedule or limit could trigger BLOCK later
        val policy = findPolicy(packageName)
        Log.d(TAG, "  [3] findPolicy($packageName): ${if (policy != null) "FOUND action=${policy.action} schedEnabled=${policy.scheduleEnabled} limitEnabled=${policy.dailyLimitEnabled}" else "NULL"}")

        if (policy == null || !policy.enabled) {
            Log.d(TAG, "  [4] No policy or disabled — no monitoring")
            monitoredPackage = null
            return result
        }

        val nextBoundaryMs = calculateNextBoundary(policy, packageName)
        Log.d(TAG, "  [5] calculateNextBoundary: ${if (nextBoundaryMs != null) "${nextBoundaryMs}ms (delay=${nextBoundaryMs - System.currentTimeMillis()}ms)" else "null"}")

        if (nextBoundaryMs != null) {
            val delayMs = nextBoundaryMs - System.currentTimeMillis()
            if (delayMs > 0) {
                monitoredPackage = packageName
                scheduleBoundary(packageName, delayMs)
                Log.d(TAG, "  [6] ✓ Monitoring $packageName — boundary in ${delayMs}ms (${delayMs / 1000}s)")
            } else {
                Log.d(TAG, "  [6] ✗ Boundary delay <= 0 — re-evaluating immediately")
                // Boundary is now or in the past — fire immediately
                monitoredPackage = packageName
                handler.post { onBoundaryFired(packageName) }
            }
        } else {
            Log.d(TAG, "  [6] No boundary to monitor")
            monitoredPackage = null
        }

        return result
    }

    /**
     * Called when a policy is updated (save/update/delete).
     * Recalculates boundary for the currently monitored package.
     */
    fun onPolicyChanged(packageName: String) {
        Log.d(TAG, "═══ onPolicyChanged: $packageName (monitored=$monitoredPackage) ═══")
        if (monitoredPackage != packageName) {
            Log.d(TAG, "  Not monitoring this package — ignoring")
            return
        }

        cancelPending()

        val policy = findPolicy(packageName)
        if (policy == null || !policy.enabled) {
            Log.d(TAG, "  Policy removed/disabled — stopping monitoring")
            monitoredPackage = null
            return
        }

        val result = policyEngine.evaluate(packageName)
        Log.d(TAG, "  Re-evaluated: action=${result.action} reason=${result.reason}")

        // If already BLOCK, enforce immediately
        if (result.action == PolicyAction.BLOCK) {
            Log.d(TAG, "  BLOCK — enforcing immediately")
            callback.onEnforce(packageName, result)
            monitoredPackage = null
            return
        }

        // Recalculate next boundary
        val nextBoundaryMs = calculateNextBoundary(policy, packageName)
        if (nextBoundaryMs != null) {
            val delayMs = nextBoundaryMs - System.currentTimeMillis()
            if (delayMs > 0) {
                scheduleBoundary(packageName, delayMs)
                Log.d(TAG, "  Rescheduled — boundary in ${delayMs}ms")
            } else {
                handler.post { onBoundaryFired(packageName) }
            }
        } else {
            Log.d(TAG, "  No boundary — stopping monitoring")
            monitoredPackage = null
        }
    }

    /**
     * Cancel all monitoring. Called when the accessibility service is destroyed.
     */
    fun cancelAll() {
        Log.d(TAG, "═══ cancelAll ═══")
        cancelPending()
        monitoredPackage = null
    }

    /**
     * Get the currently monitored package (for testing/debug).
     */
    fun getMonitoredPackage(): String? = monitoredPackage

    // -------------------------------------------------------------------------
    // Policy lookup — CRITICAL: uses repository directly
    // -------------------------------------------------------------------------

    /**
     * Find a policy by package name.
     *
     * CRITICAL: Uses [PolicyRepository] directly instead of [PolicyEngine.getPolicies]
     * because the AccessibilityService's PolicyEngine may not have the repository
     * attached and would return an empty in-memory list.
     */
    private fun findPolicy(packageName: String): Policy? {
        return try {
            if (repository != null) {
                val policy = repository.getPolicy(packageName)
                Log.d(TAG, "  findPolicy via repository: ${if (policy != null) "FOUND" else "null"}")
                policy
            } else {
                // Fallback to in-memory (only works if policies were added via setPolicy)
                val policy = policyEngine.getPolicies().find { it.packageName == packageName }
                Log.d(TAG, "  findPolicy via engine fallback: ${if (policy != null) "FOUND" else "null"}")
                policy
            }
        } catch (e: Exception) {
            Log.e(TAG, "  findPolicy FAILED: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Boundary calculation
    // -------------------------------------------------------------------------

    /**
     * Calculate the next enforcement boundary in absolute milliseconds.
     */
    internal fun calculateNextBoundary(policy: Policy, packageName: String): Long? {
        val now = System.currentTimeMillis()
        var earliest: Long? = null

        // Schedule boundary
        if (policy.scheduleEnabled && policy.schedule != null) {
            Log.d(TAG, "  calcBoundary: schedule start=${policy.schedule.startMinutes} end=${policy.schedule.endMinutes}")
            val scheduleBoundary = calculateScheduleBoundary(policy.schedule, now)
            if (scheduleBoundary != null) {
                earliest = scheduleBoundary
                Log.d(TAG, "  calcBoundary: schedule boundary = $scheduleBoundary (in ${scheduleBoundary - now}ms)")
            }
        }

        // Daily limit boundary
        if (policy.dailyLimitEnabled && policy.dailyLimit != null) {
            Log.d(TAG, "  calcBoundary: daily limit = ${policy.dailyLimit.limitMinutes} min")
            val limitBoundary = calculateLimitBoundary(policy, packageName, now)
            if (limitBoundary != null) {
                Log.d(TAG, "  calcBoundary: limit boundary = $limitBoundary (in ${limitBoundary - now}ms)")
                if (earliest == null || limitBoundary < earliest) {
                    earliest = limitBoundary
                }
            } else {
                Log.d(TAG, "  calcBoundary: limit already exceeded or no tracker")
            }
        }

        Log.d(TAG, "  calcBoundary: earliest = ${if (earliest != null) "$earliest (in ${earliest - now}ms)" else "null"}")
        return earliest
    }

    /**
     * Calculate the next schedule boundary in absolute milliseconds.
     */
    internal fun calculateScheduleBoundary(schedule: RestrictionSchedule, nowMs: Long): Long? {
        val currentMinutes = ScheduleEvaluator.currentMinutesFromMidnight()
        val start = schedule.startMinutes
        val end = schedule.endMinutes
        val nowCal = java.util.Calendar.getInstance()

        Log.d(TAG, "  calcSchedule: currentMinutes=$currentMinutes start=$start end=$end")

        return if (start <= end) {
            // Same-day window
            if (currentMinutes in start..end) {
                Log.d(TAG, "  calcSchedule: WITHIN window → boundary = end ($end)")
                minutesToEndOfDay(end, nowCal, nowMs)
            } else if (currentMinutes < start) {
                Log.d(TAG, "  calcSchedule: BEFORE window → boundary = start ($start)")
                minutesToTime(start, nowCal, nowMs)
            } else {
                Log.d(TAG, "  calcSchedule: AFTER window → boundary = start tomorrow ($start)")
                minutesToTime(start + 1440, nowCal, nowMs)
            }
        } else {
            // Overnight window
            if (currentMinutes >= start || currentMinutes <= end) {
                if (currentMinutes >= start) {
                    Log.d(TAG, "  calcSchedule: OVERNIGHT evening → boundary = end tomorrow ($end)")
                    minutesToTime(end + 1440, nowCal, nowMs)
                } else {
                    Log.d(TAG, "  calcSchedule: OVERNIGHT morning → boundary = end today ($end)")
                    minutesToEndOfDay(end, nowCal, nowMs)
                }
            } else {
                Log.d(TAG, "  calcSchedule: OUTSIDE overnight → boundary = start ($start)")
                minutesToTime(start, nowCal, nowMs)
            }
        }
    }

    /**
     * Calculate the next daily limit boundary in absolute milliseconds.
     */
    private fun calculateLimitBoundary(policy: Policy, packageName: String, nowMs: Long): Long? {
        val limit = policy.dailyLimit ?: return null
        val limitMs = limit.limitMinutes.toLong() * 60 * 1000

        val usageMs = try {
            repository?.let { repo ->
                val date = com.aiguardian.ai_guardian.policy.UsageTracker.todayDate()
                val sessions = repo.getUsageSessions(packageName, date)
                var total = 0L
                for (s in sessions) { total += s.durationMs }
                Log.d(TAG, "  calcLimit: usage from DB = ${total}ms (${total / 60000}min)")
                total
            } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "  calcLimit: Failed to get usage: ${e.message}")
            return null
        }

        if (usageMs >= limitMs) {
            Log.d(TAG, "  calcLimit: ALREADY EXCEEDED (${usageMs}ms >= ${limitMs}ms)")
            return null
        }

        val remainingMs = limitMs - usageMs
        Log.d(TAG, "  calcLimit: remaining = ${remainingMs}ms (${remainingMs / 60000}min)")
        return nowMs + remainingMs
    }

    // -------------------------------------------------------------------------
    // Scheduling helpers
    // -------------------------------------------------------------------------

    private fun scheduleBoundary(packageName: String, delayMs: Long) {
        Log.d(TAG, "  scheduleBoundary: $packageName delay=${delayMs}ms (${delayMs / 1000}s)")
        val runnable = Runnable {
            pendingRunnable = null
            Log.d(TAG, "  ★ TIMER FIRED for $packageName ★")
            onBoundaryFired(packageName)
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun onBoundaryFired(packageName: String) {
        Log.d(TAG, "═══ onBoundaryFired: $packageName ═══")

        // Verify the same package is still foreground
        if (monitoredPackage != packageName) {
            Log.d(TAG, "  monitoredPackage=$monitoredPackage != $packageName — IGNORING")
            return
        }
        Log.d(TAG, "  [A] Package verified: still monitoring $packageName")

        // Re-read latest policy
        val policy = findPolicy(packageName)
        if (policy == null || !policy.enabled) {
            Log.d(TAG, "  [B] Policy removed/disabled — stopping")
            monitoredPackage = null
            return
        }
        Log.d(TAG, "  [B] Policy re-read: action=${policy.action} schedEnabled=${policy.scheduleEnabled} limitEnabled=${policy.dailyLimitEnabled}")

        // Re-evaluate using the current engine (may have been updated since timer was scheduled)
        val result = policyEngine.evaluate(packageName)
        Log.d(TAG, "  [C] PolicyEngine re-evaluate: action=${result.action} reason=${result.reason}")

        if (result.action == PolicyAction.BLOCK) {
            Log.d(TAG, "  [D] BLOCK — calling enforcement callback")
            callback.onEnforce(packageName, result)
            monitoredPackage = null
        } else {
            Log.d(TAG, "  [D] Still ALLOW — checking for next boundary")
            val nextBoundary = calculateNextBoundary(policy, packageName)
            if (nextBoundary != null && nextBoundary > System.currentTimeMillis()) {
                val nextDelay = nextBoundary - System.currentTimeMillis()
                scheduleBoundary(packageName, nextDelay)
                Log.d(TAG, "  [E] Rescheduled next boundary in ${nextDelay}ms")
            } else {
                Log.d(TAG, "  [E] No next boundary — stopping monitoring")
                monitoredPackage = null
            }
        }
    }

    private fun cancelPending() {
        pendingRunnable?.let {
            Log.d(TAG, "  cancelPending: removing pending callback")
            handler.removeCallbacks(it)
        }
        pendingRunnable = null
    }

    // -------------------------------------------------------------------------
    // Time calculation helpers
    // -------------------------------------------------------------------------

    private fun minutesToTime(targetMinutes: Int, cal: java.util.Calendar, nowMs: Long): Long {
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val currentSeconds = cal.get(java.util.Calendar.SECOND)

        var delayMinutes = targetMinutes - currentMinutes
        if (delayMinutes < 0) {
            delayMinutes += 1440
        }

        val targetMs = nowMs + (delayMinutes.toLong() * 60 * 1000) - (currentSeconds.toLong() * 1000)
        return if (targetMs > nowMs) targetMs else nowMs + 1000
    }

    private fun minutesToEndOfDay(endMinutes: Int, cal: java.util.Calendar, nowMs: Long): Long {
        return minutesToTime(endMinutes, cal, nowMs)
    }
}
