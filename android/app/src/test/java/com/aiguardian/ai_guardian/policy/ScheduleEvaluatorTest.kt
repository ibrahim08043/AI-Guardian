package com.aiguardian.ai_guardian.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScheduleEvaluator].
 *
 * Tests time-based schedule evaluation:
 * 1. Same-day window — inside
 * 2. Same-day window — outside (before)
 * 3. Same-day window — outside (after)
 * 4. Same-day window — at boundary (start)
 * 5. Same-day window — at boundary (end)
 * 6. Overnight window — evening portion
 * 7. Overnight window — morning portion
 * 8. Overnight window — outside
 * 9. All-day window (0–1439)
 * 10. Edge case: start == end (single minute)
 */
class ScheduleEvaluatorTest {

    // -------------------------------------------------------------------------
    // Same-day window tests
    // -------------------------------------------------------------------------

    @Test
    fun `same-day window - inside returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 720)) // 12:00 PM
    }

    @Test
    fun `same-day window - before start returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 500)) // 8:20 AM
    }

    @Test
    fun `same-day window - after end returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 960)) // 4:00 PM
    }

    @Test
    fun `same-day window - at start boundary returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 600)) // 10:00 AM exactly
    }

    @Test
    fun `same-day window - at end boundary returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 900)) // 3:00 PM exactly
    }

    @Test
    fun `same-day window - midnight boundary`() {
        val schedule = RestrictionSchedule(startMinutes = 0, endMinutes = 120) // 12AM–2AM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 60))  // 1:00 AM
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 180)) // 3:00 AM
    }

    // -------------------------------------------------------------------------
    // Overnight window tests
    // -------------------------------------------------------------------------

    @Test
    fun `overnight window - evening portion returns true`() {
        // 9:00 PM → 7:00 AM
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 1380)) // 11:00 PM
    }

    @Test
    fun `overnight window - morning portion returns true`() {
        // 9:00 PM → 7:00 AM
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 300)) // 5:00 AM
    }

    @Test
    fun `overnight window - daytime returns false`() {
        // 9:00 PM → 7:00 AM
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 720)) // 12:00 PM
    }

    @Test
    fun `overnight window - at start boundary returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 1320)) // 9:00 PM exactly
    }

    @Test
    fun `overnight window - at end boundary returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 420)) // 7:00 AM exactly
    }

    @Test
    fun `overnight window - just outside morning returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 421)) // 7:01 AM
    }

    @Test
    fun `overnight window - just outside evening returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 1320, endMinutes = 420)
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 1319)) // 8:59 PM
    }

    // -------------------------------------------------------------------------
    // All-day window tests
    // -------------------------------------------------------------------------

    @Test
    fun `all-day window - any time returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 0, endMinutes = 1439)
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 0))    // 12:00 AM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 720))  // 12:00 PM
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 1439)) // 11:59 PM
    }

    // -------------------------------------------------------------------------
    // Edge case: single minute
    // -------------------------------------------------------------------------

    @Test
    fun `single minute window - exact match returns true`() {
        val schedule = RestrictionSchedule(startMinutes = 720, endMinutes = 720) // 12:00 PM only
        assertTrue(ScheduleEvaluator.isWithinSchedule(schedule, 720))
    }

    @Test
    fun `single minute window - one minute before returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 720, endMinutes = 720)
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 719))
    }

    @Test
    fun `single minute window - one minute after returns false`() {
        val schedule = RestrictionSchedule(startMinutes = 720, endMinutes = 720)
        assertFalse(ScheduleEvaluator.isWithinSchedule(schedule, 721))
    }

    // -------------------------------------------------------------------------
    // minutesRemainingInWindow tests
    // -------------------------------------------------------------------------

    @Test
    fun `minutesRemainingInWindow - inside same-day window`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900) // 10AM–3PM
        // At 12:00 PM (720), 180 minutes remain
        val remaining = ScheduleEvaluator.minutesRemainingInWindow(schedule, 720)
        assertEquals(180, remaining)
    }

    @Test
    fun `minutesRemainingInWindow - outside window returns 0`() {
        val schedule = RestrictionSchedule(startMinutes = 600, endMinutes = 900)
        val remaining = ScheduleEvaluator.minutesRemainingInWindow(schedule, 960) // 4PM
        assertEquals(0, remaining)
    }

    // -------------------------------------------------------------------------
    // Helper for minutesRemainingInWindow with custom time
    // -------------------------------------------------------------------------

    private fun ScheduleEvaluator.minutesRemainingInWindow(
        schedule: RestrictionSchedule,
        currentMinutes: Int,
    ): Int {
        if (!isWithinSchedule(schedule, currentMinutes)) return 0

        val end = schedule.endMinutes
        return when {
            schedule.startMinutes <= end -> end - currentMinutes
            currentMinutes >= schedule.startMinutes -> (1439 - currentMinutes) + end
            else -> end - currentMinutes
        }
    }

    // -------------------------------------------------------------------------
    // Constructor validation tests
    // -------------------------------------------------------------------------

    @Test
    fun `RestrictionSchedule rejects invalid start minutes`() {
        try {
            RestrictionSchedule(startMinutes = -1, endMinutes = 900)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("startMinutes") == true)
        }
    }

    @Test
    fun `RestrictionSchedule rejects invalid end minutes`() {
        try {
            RestrictionSchedule(startMinutes = 600, endMinutes = 1440)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("endMinutes") == true)
        }
    }

    @Test
    fun `DailyLimit rejects zero minutes`() {
        try {
            DailyLimit(limitMinutes = 0)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("limitMinutes") == true)
        }
    }

    @Test
    fun `DailyLimit rejects negative minutes`() {
        try {
            DailyLimit(limitMinutes = -5)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("limitMinutes") == true)
        }
    }

    @Test
    fun `DailyLimit fromMinutes returns null for zero`() {
        val limit = DailyLimit.fromMinutes(0)
        assertEquals(null, limit)
    }

    @Test
    fun `DailyLimit fromMinutes returns limit for positive`() {
        val limit = DailyLimit.fromMinutes(30)
        assertEquals(30, limit?.limitMinutes)
    }

    @Test
    fun `DailyLimit displayString formats correctly`() {
        assertEquals("15 min/day", DailyLimit(15).displayString())
        assertEquals("1 hr/day", DailyLimit(60).displayString())
        assertEquals("1h 30m/day", DailyLimit(90).displayString())
    }

    @Test
    fun `RestrictionSchedule displayString formats correctly`() {
        val schedule = RestrictionSchedule(startMinutes = 1080, endMinutes = 1140) // 6PM–7PM
        assertEquals("6:00 PM – 7:00 PM", schedule.displayString())
    }

    @Test
    fun `RestrictionSchedule formatTime formats AM correctly`() {
        val schedule = RestrictionSchedule(startMinutes = 0, endMinutes = 60)
        assertEquals("12:00 AM", schedule.formatTime(0))
        assertEquals("7:00 AM", schedule.formatTime(420))
        assertEquals("11:59 AM", schedule.formatTime(719))
    }

    @Test
    fun `RestrictionSchedule formatTime formats PM correctly`() {
        val schedule = RestrictionSchedule(startMinutes = 0, endMinutes = 60)
        assertEquals("12:00 PM", schedule.formatTime(720))
        assertEquals("6:00 PM", schedule.formatTime(1080))
        assertEquals("11:59 PM", schedule.formatTime(1439))
    }
}
