package com.aiguardian.ai_guardian.policy

import android.util.Log
import com.aiguardian.ai_guardian.storage.PolicyRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks foreground app usage sessions and calculates daily totals.
 *
 * Each time the user switches to a new foreground app, the tracker:
 * 1. Ends the previous session (if any)
 * 2. Starts a new session for the current app
 *
 * Sessions are persisted to SQLite for accuracy across app restarts.
 * Daily usage is calculated as the sum of all session durations for today.
 *
 * Privacy: Only records package name and timestamps. No screen content,
 * text, or sensitive data is recorded.
 */
open class UsageTracker(private val repository: PolicyRepository?) {

    companion object {
        private const val TAG = "AIGuardianUsage"

        /** Get today's date as YYYY-MM-DD string. */
        fun todayDate(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return sdf.format(Date())
        }
    }

    /** The currently active foreground session's package name. */
    @Volatile
    private var activePackage: String? = null

    /** Start time of the current active session. */
    @Volatile
    private var activeStartTime: Long = 0L

    /**
     * Start tracking a new foreground session.
     *
     * Called when a new app comes to the foreground. If there was a
     * previous active session, it is ended first.
     *
     * @param packageName The package name of the app now in the foreground.
     */
    fun startSession(packageName: String) {
        if (packageName.isBlank()) return

        // End previous session if one exists
        val prevPackage = activePackage
        if (prevPackage != null && prevPackage != packageName) {
            endSession()
        }

        // Don't restart tracking for the same package
        if (packageName == activePackage) return

        // Start new session
        activePackage = packageName
        activeStartTime = System.currentTimeMillis()

        Log.d(TAG, "Session started for $packageName")
    }

    /**
     * End the current active session.
     *
     * Persists the session to the database with calculated duration.
     */
    fun endSession() {
        val packageName = activePackage ?: return
        val startTime = activeStartTime
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime

        // Minimum session duration: 1 second (avoid noise from rapid switches)
        if (durationMs < 1000) {
            activePackage = null
            activeStartTime = 0L
            return
        }

        val date = todayDate()

        try {
            repository?.saveUsageSession(
                packageName = packageName,
                startTime = startTime,
                endTime = endTime,
                durationMs = durationMs,
                date = date,
            )
            Log.d(TAG, "Session ended for $packageName: ${durationMs}ms on $date")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session for $packageName: ${e.message}")
        }

        activePackage = null
        activeStartTime = 0L
    }

    /**
     * Get total usage for a package today (in milliseconds).
     *
     * Includes both completed sessions from the database and the
     * current active session (if applicable).
     *
     * @param packageName The package to query.
     * @return Total usage in milliseconds for today.
     */
    open fun getUsageTodayMs(packageName: String): Long {
        if (packageName.isBlank()) return 0L

        val today = todayDate()
        var totalMs = 0L

        try {
            // Sum completed sessions from database
            val sessions = repository?.getUsageSessions(packageName, today)
            if (sessions != null) {
                for (session in sessions) {
                    totalMs += session.durationMs
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage for $packageName: ${e.message}")
        }

        // Add current active session if it's for this package
        if (activePackage == packageName && activeStartTime > 0) {
            totalMs += System.currentTimeMillis() - activeStartTime
        }

        return totalMs
    }

    /**
     * Get total usage for a package today (in minutes, rounded down).
     */
    fun getUsageTodayMinutes(packageName: String): Int {
        return (getUsageTodayMs(packageName) / (60 * 1000)).toInt()
    }

    /**
     * Check if a package has exceeded its daily limit.
     *
     * @param packageName The package to check.
     * @param limitMinutes The daily limit in minutes.
     * @return true if usage >= limit.
     */
    open fun hasExceededLimit(packageName: String, limitMinutes: Int): Boolean {
        if (limitMinutes <= 0) return false
        return getUsageTodayMs(packageName) >= (limitMinutes.toLong() * 60 * 1000)
    }

    /**
     * Clear the active session state (for testing or cleanup).
     */
    fun resetActiveSession() {
        activePackage = null
        activeStartTime = 0L
    }

    /**
     * Get the active session info (for debug UI).
     */
    fun getActiveSession(): ActiveSession? {
        val pkg = activePackage ?: return null
        return ActiveSession(
            packageName = pkg,
            startTime = activeStartTime,
            durationMs = if (activeStartTime > 0) System.currentTimeMillis() - activeStartTime else 0L,
        )
    }

    data class ActiveSession(
        val packageName: String,
        val startTime: Long,
        val durationMs: Long,
    )
}
