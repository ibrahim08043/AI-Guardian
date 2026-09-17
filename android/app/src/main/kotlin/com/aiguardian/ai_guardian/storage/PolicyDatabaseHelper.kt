package com.aiguardian.ai_guardian.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite database helper for persistent policy storage.
 *
 * Manages the local SQLite database that stores all policy configurations
 * including schedules, daily limits, and usage sessions.
 * Uses standard Android SQLiteOpenHelper for database lifecycle management.
 *
 * Database:
 * - Name: ai_guardian.db
 * - Version: 2 (Phase C upgrade)
 * - Stored locally on device
 *
 * Security:
 * - Uses parameterized queries (no SQL injection)
 * - All queries are safe from malformed input
 * - Database file is private to this app
 *
 * Migration:
 * - v1 → v2: Added schedule, daily limit columns to policies table.
 *             Created usage_sessions table.
 *             Existing user policies are preserved.
 */
class PolicyDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val TAG = "AIGuardianDB"
        private const val DATABASE_NAME = "ai_guardian.db"
        private const val DATABASE_VERSION = 3

        // Table name
        const val TABLE_POLICIES = "policies"

        // Column names — policies table
        const val COLUMN_ID = "id"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_ACTION = "action"
        const val COLUMN_ENABLED = "enabled"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"

        // Phase C: Schedule columns
        const val COLUMN_SCHEDULE_ENABLED = "schedule_enabled"
        const val COLUMN_SCHEDULE_START = "schedule_start"   // minutes from midnight (0–1439)
        const val COLUMN_SCHEDULE_END = "schedule_end"       // minutes from midnight (0–1439)

        // Phase C: Daily limit columns
        const val COLUMN_DAILY_LIMIT_ENABLED = "daily_limit_enabled"
        const val COLUMN_DAILY_LIMIT_MINUTES = "daily_limit_minutes"  // 0 = unlimited

        // Usage sessions table
        const val TABLE_USAGE_SESSIONS = "usage_sessions"
        const val COLUMN_SESSION_ID = "id"
        const val COLUMN_SESSION_PACKAGE = "package_name"
        const val COLUMN_SESSION_START = "start_time"
        const val COLUMN_SESSION_END = "end_time"
        const val COLUMN_SESSION_DURATION = "duration_ms"
        const val COLUMN_SESSION_DATE = "date"  // YYYY-MM-DD for daily aggregation

        // Domain blocking table (Phase K)
        const val TABLE_DOMAINS = "domains"
        const val COLUMN_DOMAIN = "domain"
        const val COLUMN_DOMAIN_ENABLED = "enabled"
        const val COLUMN_DOMAIN_CREATED_AT = "created_at"
        const val COLUMN_DOMAIN_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createPoliciesTable(db)
        createUsageSessionsTable(db)
        createDomainsTable(db)
        Log.i(TAG, "Database tables created successfully (v$DATABASE_VERSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Database upgrade from v$oldVersion to v$newVersion")

        if (oldVersion < 2) {
            migrateV1ToV2(db)
        }
        if (oldVersion < 3) {
            migrateV2ToV3(db)
        }
    }

    /**
     * Migration from v1 to v2:
     * - Add schedule columns to policies table (nullable, so existing rows unaffected)
     * - Add daily limit columns to policies table (nullable, so existing rows unaffected)
     * - Create usage_sessions table
     *
     * All existing user policies are preserved because new columns are nullable
     * with sensible defaults.
     */
    private fun migrateV1ToV2(db: SQLiteDatabase) {
        try {
            // Add schedule columns — nullable, existing rows get NULL (schedule disabled)
            db.execSQL("ALTER TABLE $TABLE_POLICIES ADD COLUMN $COLUMN_SCHEDULE_ENABLED INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_POLICIES ADD COLUMN $COLUMN_SCHEDULE_START INTEGER")
            db.execSQL("ALTER TABLE $TABLE_POLICIES ADD COLUMN $COLUMN_SCHEDULE_END INTEGER")

            // Add daily limit columns — nullable, existing rows get NULL (limit disabled)
            db.execSQL("ALTER TABLE $TABLE_POLICIES ADD COLUMN $COLUMN_DAILY_LIMIT_ENABLED INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_POLICIES ADD COLUMN $COLUMN_DAILY_LIMIT_MINUTES INTEGER")

            // Create usage sessions table
            createUsageSessionsTable(db)

            Log.i(TAG, "Migration v1→v2 completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Migration v1→v2 failed: ${e.message}")
            throw e
        }
    }

    private fun createPoliciesTable(db: SQLiteDatabase) {
        val sql = """
            CREATE TABLE $TABLE_POLICIES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PACKAGE_NAME TEXT NOT NULL UNIQUE,
                $COLUMN_ACTION TEXT NOT NULL,
                $COLUMN_ENABLED INTEGER NOT NULL DEFAULT 1,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_SCHEDULE_ENABLED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SCHEDULE_START INTEGER,
                $COLUMN_SCHEDULE_END INTEGER,
                $COLUMN_DAILY_LIMIT_ENABLED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_DAILY_LIMIT_MINUTES INTEGER
            )
        """.trimIndent()
        db.execSQL(sql)
    }

    private fun createUsageSessionsTable(db: SQLiteDatabase) {
        val sql = """
            CREATE TABLE $TABLE_USAGE_SESSIONS (
                $COLUMN_SESSION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SESSION_PACKAGE TEXT NOT NULL,
                $COLUMN_SESSION_START INTEGER NOT NULL,
                $COLUMN_SESSION_END INTEGER,
                $COLUMN_SESSION_DURATION INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SESSION_DATE TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(sql)

        // Index for efficient daily queries
        db.execSQL(
            "CREATE INDEX idx_usage_package_date ON $TABLE_USAGE_SESSIONS" +
                    "($COLUMN_SESSION_PACKAGE, $COLUMN_SESSION_DATE)"
        )
    }

    /**
     * Migration from v2 to v3: Add domain blocking table.
     */
    private fun migrateV2ToV3(db: SQLiteDatabase) {
        try {
            createDomainsTable(db)
            Log.i(TAG, "Migration v2→v3 completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Migration v2→v3 failed: ${e.message}")
            throw e
        }
    }

    private fun createDomainsTable(db: SQLiteDatabase) {
        val sql = """
            CREATE TABLE $TABLE_DOMAINS (
                $COLUMN_DOMAIN TEXT NOT NULL PRIMARY KEY,
                $COLUMN_DOMAIN_ENABLED INTEGER NOT NULL DEFAULT 1,
                $COLUMN_DOMAIN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_DOMAIN_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(sql)
    }

    /**
     * Safe database access wrapper.
     * Returns the database, or null if open fails.
     */
    fun getReadableDB(): SQLiteDatabase? = try {
        readableDatabase
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open readable database: ${e.message}")
        null
    }

    /**
     * Safe database access wrapper for writes.
     * Returns the database, or null if open fails.
     */
    fun getWritableDB(): SQLiteDatabase? = try {
        writableDatabase
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open writable database: ${e.message}")
        null
    }
}
