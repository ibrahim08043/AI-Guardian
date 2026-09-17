package com.aiguardian.ai_guardian.policy

/**
 * A single deterministic policy rule.
 *
 * Each policy maps a package name to an action (ALLOW or BLOCK) with optional
 * smart restriction rules:
 * - **Schedule**: Block only during specific time windows
 * - **Daily Limit**: Block after exceeding a daily usage quota
 *
 * Policies can be individually enabled or disabled.
 *
 * This is a pure data class — no logic, no side effects.
 */
data class Policy(
    /** The Android package name this policy applies to. */
    val packageName: String,

    /** The base action to take when this policy matches (ALLOW or BLOCK). */
    val action: PolicyAction,

    /** Whether this policy is currently active. */
    val enabled: Boolean = true,

    // --- Phase C: Smart Restriction Rules ---

    /** Optional time-based blocking schedule. null = no schedule restriction. */
    val schedule: RestrictionSchedule? = null,

    /** Whether the schedule restriction is enabled. */
    val scheduleEnabled: Boolean = false,

    /** Optional daily usage limit. null = no usage limit. */
    val dailyLimit: DailyLimit? = null,

    /** Whether the daily limit restriction is enabled. */
    val dailyLimitEnabled: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) { "Policy packageName must not be blank" }
    }
}
