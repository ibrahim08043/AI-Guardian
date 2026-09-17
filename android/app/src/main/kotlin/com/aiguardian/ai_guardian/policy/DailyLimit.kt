package com.aiguardian.ai_guardian.policy

/**
 * A daily usage limit for a policy.
 *
 * Defines the maximum number of minutes an app can be used per day.
 * When accumulated foreground usage reaches the limit, the app is blocked.
 *
 * Usage tracking is session-based:
 * - Each foreground session is recorded with start/end timestamps
 * - Daily usage is the sum of all session durations for the current date
 * - Usage resets at midnight (local time)
 *
 * This is a pure data class — no logic, no side effects.
 * Usage tracking logic lives in [UsageTracker].
 */
data class DailyLimit(
    /** Maximum allowed usage per day in minutes. */
    val limitMinutes: Int,
) {
    init {
        require(limitMinutes > 0) {
            "limitMinutes must be > 0, got $limitMinutes"
        }
    }

    /** Human-readable display string. */
    fun displayString(): String {
        return when {
            limitMinutes < 60 -> "$limitMinutes min/day"
            limitMinutes % 60 == 0 -> "${limitMinutes / 60} hr/day"
            else -> "${limitMinutes / 60}h ${limitMinutes % 60}m/day"
        }
    }

    /** Convert limit to milliseconds for comparison with session durations. */
    fun limitMs(): Long = limitMinutes.toLong() * 60 * 1000

    companion object {
        /** Create from raw minute value (for database deserialization). */
        fun fromMinutes(minutes: Int): DailyLimit? {
            return if (minutes > 0) DailyLimit(minutes) else null
        }
    }
}
