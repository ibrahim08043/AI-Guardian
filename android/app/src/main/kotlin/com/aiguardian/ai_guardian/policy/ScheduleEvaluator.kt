package com.aiguardian.ai_guardian.policy

import android.util.Log
import java.util.Calendar

/**
 * Evaluates whether the current time falls within a restriction schedule.
 *
 * Uses minutes-from-midnight (0–1439) for time representation.
 * Supports both same-day windows and overnight windows.
 *
 * Examples:
 * - 6:00 PM → 9:00 PM (1080–1140): blocks during evening
 * - 10:00 PM → 7:00 AM (1380–420): overnight block
 * - 12:00 AM → 11:59 PM (0–1439): all-day block
 *
 * This is a pure evaluator — no state, no side effects.
 */
object ScheduleEvaluator {

    private const val TAG = "AIGuardianSchedule"

    /**
     * Check if the current time is within the given schedule window.
     *
     * @param schedule The restriction schedule to evaluate.
     * @return true if current time is inside the block window.
     */
    fun isWithinSchedule(schedule: RestrictionSchedule): Boolean {
        return isWithinSchedule(schedule, currentMinutesFromMidnight())
    }

    /**
     * Check if a specific time (in minutes from midnight) falls within
     * the given schedule window.
     *
     * @param schedule The restriction schedule to evaluate.
     * @param currentMinutes Current time as minutes from midnight (0–1439).
     * @return true if the time is inside the block window.
     */
    fun isWithinSchedule(schedule: RestrictionSchedule, currentMinutes: Int): Boolean {
        val start = schedule.startMinutes
        val end = schedule.endMinutes

        return when {
            // Same-day window: e.g., 6:00 PM → 9:00 PM (1080 → 1140)
            start <= end -> {
                currentMinutes in start..end
            }

            // Overnight window: e.g., 10:00 PM → 7:00 AM (1380 → 420)
            // Current time is in block window if:
            // - After start (evening side): current >= start
            // - Before end (morning side): current <= end
            start > end -> {
                currentMinutes >= start || currentMinutes <= end
            }

            else -> false
        }
    }

    /**
     * Check if ANY enabled schedule in the list is currently active.
     * Used by PolicyEngine for multi-schedule evaluation (OR logic).
     *
     * @param schedules List of restriction schedules to evaluate.
     * @return true if at least one enabled schedule is currently active.
     */
    fun anyActiveSchedule(schedules: List<RestrictionSchedule>): Boolean {
        return schedules.any { it.enabled && isWithinSchedule(it) }
    }

    /**
     * Get current time as minutes from midnight (0–1439).
     */
    fun currentMinutesFromMidnight(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /**
     * Get the remaining minutes in the current block window.
     * Returns 0 if not currently in the window.
     *
     * @param schedule The restriction schedule.
     * @return Minutes remaining until the block window ends.
     */
    fun minutesRemainingInWindow(schedule: RestrictionSchedule): Int {
        if (!isWithinSchedule(schedule)) return 0

        val current = currentMinutesFromMidnight()
        val end = schedule.endMinutes

        return when {
            // Same-day window
            schedule.startMinutes <= end -> {
                end - current
            }

            // Overnight window — currently in evening portion
            current >= schedule.startMinutes -> {
                // Minutes until midnight + end minutes
                (1439 - current) + end
            }

            // Overnight window — currently in morning portion
            else -> {
                end - current
            }
        }
    }
}
