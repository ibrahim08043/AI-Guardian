package com.aiguardian.ai_guardian.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aiguardian.ai_guardian.policy.DailyLimit
import com.aiguardian.ai_guardian.policy.Policy
import com.aiguardian.ai_guardian.policy.PolicyAction
import com.aiguardian.ai_guardian.policy.RestrictionSchedule
import com.aiguardian.ai_guardian.policy.UsageTracker
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_ACTION
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_CREATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DAILY_LIMIT_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DAILY_LIMIT_MINUTES
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_ID
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_PACKAGE_NAME
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHEDULE_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHEDULE_END
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHEDULE_START
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_DATE
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_UPDATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_DURATION
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_END
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_PACKAGE
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_START
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SESSION_ID
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.TABLE_POLICIES
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.TABLE_USAGE_SESSIONS
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.TABLE_APP_SCHEDULES
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_ID
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_PACKAGE
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_START
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_END
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_CREATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_SCHED_UPDATED_AT

/**
 * Repository layer for persistent policy storage.
 *
 * Manages all CRUD operations for policies, schedules, daily limits,
 * and usage sessions using SQLite. All database access is parameterized
 * to prevent SQL injection. Database errors are logged but do not crash.
 *
 * This repository is thread-safe for reads but callers should
 * synchronize writes if needed.
 */
class PolicyRepository(private val dbHelper: PolicyDatabaseHelper) {

    companion object {
        private const val TAG = "AIGuardianRepository"
    }

    // -------------------------------------------------------------------------
    // Read operations — Policies
    // -------------------------------------------------------------------------

    /**
     * Get all policies from the database.
     * Returns an empty list if the database is unavailable.
     */
    fun getAllPolicies(): List<Policy> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(TABLE_POLICIES, null, null, null, null, null, null)

            val policies = mutableListOf<Policy>()
            cursor.use {
                while (it.moveToNext()) {
                    val policy = readPolicyFromCursor(it)
                    if (policy != null) {
                        policies.add(policy)
                    }
                }
            }

            Log.d(TAG, "Loaded ${policies.size} policies from database")
            policies
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read policies: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get a specific policy by package name.
     * Returns null if not found or if database is unavailable.
     */
    fun getPolicy(packageName: String): Policy? {
        if (packageName.isBlank()) {
            Log.w(TAG, "Cannot query policy with blank package name")
            return null
        }

        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return null
        }

        return try {
            val cursor = db.query(
                TABLE_POLICIES,
                null,
                "$COLUMN_PACKAGE_NAME = ?",
                arrayOf(packageName),
                null, null, null,
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val policy = readPolicyFromCursor(it)
                    if (policy != null) {
                        Log.d(TAG, "Found policy for $packageName: ${policy.action} (${policy.enabled})")
                    }
                    policy
                } else {
                    Log.d(TAG, "No policy found for $packageName")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read policy for $packageName: ${e.message}")
            null
        }
    }

    /**
     * Read a Policy from a cursor row, including Phase C fields.
     * Also loads schedules from the app_schedules table.
     */
    private fun readPolicyFromCursor(cursor: android.database.Cursor): Policy? {
        return try {
            val packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME))
            val actionStr = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ACTION))
            val enabled = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ENABLED)) != 0

            // Phase C: Daily limit fields
            val dailyLimitEnabled = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DAILY_LIMIT_ENABLED)) != 0
            val dailyLimitMinutes = if (!cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_DAILY_LIMIT_MINUTES))) {
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DAILY_LIMIT_MINUTES))
            } else null

            val action = try {
                PolicyAction.valueOf(actionStr)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid action '$actionStr' for package $packageName")
                return null
            }

            // Load schedules from app_schedules table
            val schedules = getSchedulesForPackage(packageName)

            // Build daily limit if present
            val dailyLimit = dailyLimitMinutes?.let { minutes ->
                try {
                    DailyLimit.fromMinutes(minutes)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid daily limit for $packageName: ${e.message}")
                    null
                }
            }

            Policy(
                packageName = packageName,
                action = action,
                enabled = enabled,
                schedules = schedules,
                dailyLimit = dailyLimit,
                dailyLimitEnabled = dailyLimitEnabled,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read policy from cursor: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Write operations — Policies
    // -------------------------------------------------------------------------

    /**
     * Save a new policy to the database.
     * If a policy for this package already exists, updatePolicy() is called instead.
     * Returns true if successful, false otherwise.
     */
    fun savePolicy(policy: Policy): Boolean {
        if (policy.packageName.isBlank()) {
            Log.w(TAG, "Cannot save policy with blank package name")
            return false
        }

        // Check if policy already exists
        if (getPolicy(policy.packageName) != null) {
            Log.d(TAG, "Policy already exists for ${policy.packageName}, updating instead")
            return updatePolicy(policy)
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = buildPolicyContentValues(policy)
            values.put(COLUMN_CREATED_AT, System.currentTimeMillis())

            val result = db.insert(TABLE_POLICIES, null, values)
            val success = result > 0

            if (success) {
                Log.i(TAG, "Saved policy: ${policy.packageName} → ${policy.action}")
            } else {
                Log.e(TAG, "Failed to save policy: ${policy.packageName}")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while saving policy: ${e.message}")
            false
        }
    }

    /**
     * Update an existing policy.
     * Returns true if successful, false otherwise.
     */
    fun updatePolicy(policy: Policy): Boolean {
        if (policy.packageName.isBlank()) {
            Log.w(TAG, "Cannot update policy with blank package name")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = buildPolicyContentValues(policy)

            val result = db.update(
                TABLE_POLICIES,
                values,
                "$COLUMN_PACKAGE_NAME = ?",
                arrayOf(policy.packageName),
            )

            val success = result > 0

            if (success) {
                Log.i(TAG, "Updated policy: ${policy.packageName} → ${policy.action}")
            } else {
                Log.w(TAG, "No policy found to update: ${policy.packageName}")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating policy: ${e.message}")
            false
        }
    }

    /**
     * Build ContentValues for a policy, including Phase C fields.
     */
    private fun buildPolicyContentValues(policy: Policy): ContentValues {
        return ContentValues().apply {
            put(COLUMN_PACKAGE_NAME, policy.packageName)
            put(COLUMN_ACTION, policy.action.name)
            put(COLUMN_ENABLED, if (policy.enabled) 1 else 0)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())

            // Phase C: Schedule — now stored in app_schedules table
            // Keep old columns as NULL for backward compat
            put(COLUMN_SCHEDULE_ENABLED, 0)
            putNull(COLUMN_SCHEDULE_START)
            putNull(COLUMN_SCHEDULE_END)

            // Phase C: Daily limit
            put(COLUMN_DAILY_LIMIT_ENABLED, if (policy.dailyLimitEnabled) 1 else 0)
            if (policy.dailyLimit != null) {
                put(COLUMN_DAILY_LIMIT_MINUTES, policy.dailyLimit.limitMinutes)
            } else {
                putNull(COLUMN_DAILY_LIMIT_MINUTES)
            }
        }
    }

    /**
     * Delete a policy by package name.
     * Returns true if successful, false otherwise.
     */
    fun deletePolicy(packageName: String): Boolean {
        if (packageName.isBlank()) {
            Log.w(TAG, "Cannot delete policy with blank package name")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(
                TABLE_POLICIES,
                "$COLUMN_PACKAGE_NAME = ?",
                arrayOf(packageName),
            )

            // Also delete all schedules for this package
            deleteSchedulesForPackage(packageName)

            val success = result > 0

            if (success) {
                Log.i(TAG, "Deleted policy: $packageName")
            } else {
                Log.w(TAG, "No policy found to delete: $packageName")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while deleting policy: ${e.message}")
            false
        }
    }

    /**
     * Enable or disable a policy by package name.
     * Returns true if successful, false otherwise.
     */
    fun setPolicyEnabled(packageName: String, enabled: Boolean): Boolean {
        if (packageName.isBlank()) {
            Log.w(TAG, "Cannot update policy with blank package name")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_POLICIES,
                values,
                "$COLUMN_PACKAGE_NAME = ?",
                arrayOf(packageName),
            )

            val success = result > 0

            if (success) {
                Log.i(TAG, "Set policy enabled=$enabled for $packageName")
            } else {
                Log.w(TAG, "No policy found to update: $packageName")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating policy enabled state: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Phase C: Schedule operations
    // -------------------------------------------------------------------------

    /**
     * Get all schedules for a package from the app_schedules table.
     */
    fun getSchedulesForPackage(packageName: String): List<RestrictionSchedule> {
        if (packageName.isBlank()) return emptyList()

        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_APP_SCHEDULES,
                null,
                "$COLUMN_SCHED_PACKAGE = ?",
                arrayOf(packageName),
                null, null,
                "$COLUMN_SCHED_CREATED_AT ASC",
            )

            val schedules = mutableListOf<RestrictionSchedule>()
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(COLUMN_SCHED_ID))
                    val start = it.getInt(it.getColumnIndexOrThrow(COLUMN_SCHED_START))
                    val end = it.getInt(it.getColumnIndexOrThrow(COLUMN_SCHED_END))
                    val schedEnabled = it.getInt(it.getColumnIndexOrThrow(COLUMN_SCHED_ENABLED)) != 0

                    try {
                        schedules.add(
                            RestrictionSchedule(
                                startMinutes = start,
                                endMinutes = end,
                                id = id,
                                enabled = schedEnabled,
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid schedule row id=$id for $packageName: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Loaded ${schedules.size} schedules for $packageName")
            schedules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get schedules for $packageName: ${e.message}")
            emptyList()
        }
    }

    /**
     * Save a new schedule for a package.
     * Returns the inserted row ID, or -1 on failure.
     */
    fun saveSchedule(
        packageName: String,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean = true,
    ): Long {
        if (packageName.isBlank()) return -1

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return -1
        }

        return try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COLUMN_SCHED_PACKAGE, packageName)
                put(COLUMN_SCHED_START, startMinutes)
                put(COLUMN_SCHED_END, endMinutes)
                put(COLUMN_SCHED_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_SCHED_CREATED_AT, now)
                put(COLUMN_SCHED_UPDATED_AT, now)
            }

            val result = db.insert(TABLE_APP_SCHEDULES, null, values)
            if (result > 0) {
                Log.i(TAG, "Saved schedule for $packageName: $startMinutes→$endMinutes (id=$result)")
            } else {
                Log.e(TAG, "Failed to save schedule for $packageName")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception while saving schedule: ${e.message}")
            -1
        }
    }

    /**
     * Update an existing schedule by ID.
     * Returns true if successful, false otherwise.
     */
    fun updateScheduleById(
        id: Long,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean,
    ): Boolean {
        if (id <= 0) return false

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_SCHED_START, startMinutes)
                put(COLUMN_SCHED_END, endMinutes)
                put(COLUMN_SCHED_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_SCHED_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_APP_SCHEDULES,
                values,
                "$COLUMN_SCHED_ID = ?",
                arrayOf(id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Updated schedule id=$id: $startMinutes→$endMinutes enabled=$enabled")
            } else {
                Log.w(TAG, "No schedule found to update: id=$id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating schedule: ${e.message}")
            false
        }
    }

    /**
     * Delete a schedule by ID.
     * Returns true if successful, false otherwise.
     */
    fun deleteScheduleById(id: Long): Boolean {
        if (id <= 0) return false

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(
                TABLE_APP_SCHEDULES,
                "$COLUMN_SCHED_ID = ?",
                arrayOf(id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Deleted schedule id=$id")
            } else {
                Log.w(TAG, "No schedule found to delete: id=$id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while deleting schedule: ${e.message}")
            false
        }
    }

    /**
     * Toggle the enabled state of a schedule by ID.
     * Returns true if successful, false otherwise.
     */
    fun toggleScheduleEnabled(id: Long, enabled: Boolean): Boolean {
        if (id <= 0) return false

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_SCHED_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_SCHED_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_APP_SCHEDULES,
                values,
                "$COLUMN_SCHED_ID = ?",
                arrayOf(id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Toggled schedule id=$id enabled=$enabled")
            } else {
                Log.w(TAG, "No schedule found to toggle: id=$id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while toggling schedule: ${e.message}")
            false
        }
    }

    /**
     * Delete all schedules for a package.
     * Called when a policy is deleted.
     */
    fun deleteSchedulesForPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(
                TABLE_APP_SCHEDULES,
                "$COLUMN_SCHED_PACKAGE = ?",
                arrayOf(packageName),
            )
            Log.i(TAG, "Deleted $result schedules for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while deleting schedules for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Legacy updateSchedule for backward compatibility.
     * Delegates to the new saveSchedule method.
     */
    fun updateSchedule(
        packageName: String,
        scheduleEnabled: Boolean,
        startMinutes: Int?,
        endMinutes: Int?,
    ): Boolean {
        if (packageName.isBlank()) return false
        if (!scheduleEnabled || startMinutes == null || endMinutes == null) {
            // Disabling or clearing schedule — delete all schedules for this package
            return deleteSchedulesForPackage(packageName)
        }
        // Save a new schedule
        val id = saveSchedule(packageName, startMinutes, endMinutes, true)
        return id > 0
    }

    // -------------------------------------------------------------------------
    // Phase C: Daily limit operations
    // -------------------------------------------------------------------------

    /**
     * Update the daily limit for a policy.
     * Returns true if successful, false otherwise.
     */
    fun updateDailyLimit(
        packageName: String,
        dailyLimitEnabled: Boolean,
        limitMinutes: Int?,
    ): Boolean {
        if (packageName.isBlank()) return false

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_DAILY_LIMIT_ENABLED, if (dailyLimitEnabled) 1 else 0)
                if (limitMinutes != null && limitMinutes > 0) {
                    put(COLUMN_DAILY_LIMIT_MINUTES, limitMinutes)
                } else {
                    putNull(COLUMN_DAILY_LIMIT_MINUTES)
                }
                put(COLUMN_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_POLICIES,
                values,
                "$COLUMN_PACKAGE_NAME = ?",
                arrayOf(packageName),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Updated daily limit for $packageName: enabled=$dailyLimitEnabled, limit=$limitMinutes")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating daily limit: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Phase C: Usage session operations
    // -------------------------------------------------------------------------

    /**
     * Save a usage session to the database.
     */
    fun saveUsageSession(
        packageName: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
        date: String,
    ): Boolean {
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_SESSION_PACKAGE, packageName)
                put(COLUMN_SESSION_START, startTime)
                put(COLUMN_SESSION_END, endTime)
                put(COLUMN_SESSION_DURATION, durationMs)
                put(COLUMN_SESSION_DATE, date)
            }

            val result = db.insert(TABLE_USAGE_SESSIONS, null, values)
            result > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save usage session: ${e.message}")
            false
        }
    }

    /**
     * Get all usage sessions for a package on a specific date.
     */
    fun getUsageSessions(packageName: String, date: String): List<UsageSession> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_USAGE_SESSIONS,
                null,
                "$COLUMN_SESSION_PACKAGE = ? AND $COLUMN_SESSION_DATE = ?",
                arrayOf(packageName, date),
                null, null, "$COLUMN_SESSION_START ASC",
            )

            val sessions = mutableListOf<UsageSession>()
            cursor.use {
                while (it.moveToNext()) {
                    val session = UsageSession(
                        id = it.getLong(it.getColumnIndexOrThrow(COLUMN_SESSION_ID)),
                        packageName = it.getString(it.getColumnIndexOrThrow(COLUMN_SESSION_PACKAGE)),
                        startTime = it.getLong(it.getColumnIndexOrThrow(COLUMN_SESSION_START)),
                        endTime = it.getLong(it.getColumnIndexOrThrow(COLUMN_SESSION_END)),
                        durationMs = it.getLong(it.getColumnIndexOrThrow(COLUMN_SESSION_DURATION)),
                        date = it.getString(it.getColumnIndexOrThrow(COLUMN_SESSION_DATE)),
                    )
                    sessions.add(session)
                }
            }

            sessions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage sessions: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get total usage duration for a package on a specific date.
     */
    fun getTotalUsageMs(packageName: String, date: String): Long {
        val sessions = getUsageSessions(packageName, date)
        return sessions.sumOf { it.durationMs }
    }

    /**
     * Clear all policies from the database.
     * ONLY for testing purposes.
     * Returns true if successful, false otherwise.
     */
    fun clearAllPolicies(): Boolean {
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(TABLE_POLICIES, null, null)
            Log.i(TAG, "Cleared $result policies from database")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while clearing policies: ${e.message}")
            false
        }
    }

    /**
     * Clear all usage sessions from the database.
     * ONLY for testing purposes.
     */
    fun clearAllUsageSessions(): Boolean {
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(TABLE_USAGE_SESSIONS, null, null)
            Log.i(TAG, "Cleared $result usage sessions from database")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while clearing usage sessions: ${e.message}")
            false
        }
    }

    /**
     * Data class for usage sessions read from the database.
     */
    data class UsageSession(
        val id: Long,
        val packageName: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val date: String,
    )
}
