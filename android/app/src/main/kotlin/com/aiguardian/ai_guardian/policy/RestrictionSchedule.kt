package com.aiguardian.ai_guardian.policy

/**
 * A time-based blocking schedule for a policy.
 *
 * Defines a daily time window during which an app should be blocked.
 * Times are represented as minutes from midnight (0–1439).
 *
 * Examples:
 * - 6:00 PM → 9:00 PM = start=1080, end=1140
 * - 10:00 PM → 7:00 AM = start=1380, end=420 (overnight)
 * - 12:00 AM → 11:59 PM = start=0, end=1439 (all day)
 *
 * This is a pure data class — no logic, no side effects.
 * Schedule evaluation logic lives in [ScheduleEvaluator].
 */
data class RestrictionSchedule(
    /** Minutes from midnight when blocking starts (0–1439). */
    val startMinutes: Int,

    /** Minutes from midnight when blocking ends (0–1439). */
    val endMinutes: Int,

    /** Database row ID. 0 for in-memory schedules not yet persisted. */
    val id: Long = 0,

    /** Whether this individual schedule is enabled. */
    val enabled: Boolean = true,
) {
    init {
        require(startMinutes in 0..1439) {
            "startMinutes must be 0–1439, got $startMinutes"
        }
        require(endMinutes in 0..1439) {
            "endMinutes must be 0–1439, got $endMinutes"
        }
    }

    /**
     * Convert minutes-from-midnight to a formatted time string.
     * Example: 1080 → "6:00 PM", 420 → "7:00 AM"
     */
    fun formatTime(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        val period = if (hours < 12) "AM" else "PM"
        val displayHour = when {
            hours == 0 -> 12
            hours > 12 -> hours - 12
            else -> hours
        }
        return "$displayHour:${mins.toString().padStart(2, '0')} $period"
    }

    /** Human-readable display string. */
    fun displayString(): String {
        return "${formatTime(startMinutes)} – ${formatTime(endMinutes)}"
    }

    companion object {
        /**
         * Create a schedule from hour and minute components.
         * @param startHour 0–23
         * @param startMin 0–59
         * @param endHour 0–23
         * @param endMin 0–59
         */
        fun fromTime(
            startHour: Int, startMin: Int,
            endHour: Int, endMin: Int,
        ): RestrictionSchedule {
            require(startHour in 0..23 && startMin in 0..59)
            require(endHour in 0..23 && endMin in 0..59)
            return RestrictionSchedule(
                startMinutes = startHour * 60 + startMin,
                endMinutes = endHour * 60 + endMin,
            )
        }

        /** Create from raw minute values (for database deserialization). */
        fun fromMinutes(startMinutes: Int, endMinutes: Int): RestrictionSchedule {
            return RestrictionSchedule(startMinutes, endMinutes)
        }
    }
}
