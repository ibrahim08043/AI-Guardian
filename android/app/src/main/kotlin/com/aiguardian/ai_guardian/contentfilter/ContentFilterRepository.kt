package com.aiguardian.ai_guardian.contentfilter

import android.content.ContentValues
import android.util.Log
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_CREATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_ID
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_MATCH_MODE
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_PHRASE
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_FILTER_UPDATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.TABLE_CONTENT_FILTER_RULES

/**
 * Repository for content filter rule CRUD operations.
 *
 * Follows the same pattern as [PolicyRepository]: wraps [PolicyDatabaseHelper],
 * uses parameterized queries, logs errors without crashing.
 *
 * All writes are immediate (no batching). Thread-safe for reads; callers
 * should synchronize writes if needed.
 */
class ContentFilterRepository(private val dbHelper: PolicyDatabaseHelper) {

    companion object {
        private const val TAG = "AIGuardianFilterRepo"
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Get all content filter rules, ordered by created_at ascending.
     * Returns an empty list if the database is unavailable.
     */
    fun getAllRules(): List<ContentFilterRule> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_CONTENT_FILTER_RULES,
                null, null, null, null, null,
                "$COLUMN_FILTER_CREATED_AT ASC",
            )

            val rules = mutableListOf<ContentFilterRule>()
            cursor.use {
                while (it.moveToNext()) {
                    val rule = readRuleFromCursor(it)
                    if (rule != null) {
                        rules.add(rule)
                    }
                }
            }

            Log.d(TAG, "Loaded ${rules.size} content filter rules")
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read content filter rules: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get all enabled content filter rules.
     * Used by ContentFilterEngine for matching — only loads active rules.
     */
    fun getEnabledRules(): List<ContentFilterRule> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_CONTENT_FILTER_RULES,
                null,
                "$COLUMN_FILTER_ENABLED = 1",
                null, null, null,
                "$COLUMN_FILTER_CREATED_AT ASC",
            )

            val rules = mutableListOf<ContentFilterRule>()
            cursor.use {
                while (it.moveToNext()) {
                    val rule = readRuleFromCursor(it)
                    if (rule != null) {
                        rules.add(rule)
                    }
                }
            }

            Log.d(TAG, "Loaded ${rules.size} enabled content filter rules")
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read enabled content filter rules: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get a single rule by ID.
     * Returns null if not found or if database is unavailable.
     */
    fun getRule(id: Long): ContentFilterRule? {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return null
        }

        return try {
            val cursor = db.query(
                TABLE_CONTENT_FILTER_RULES,
                null,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
                null, null, null,
            )

            cursor.use {
                if (it.moveToFirst()) readRuleFromCursor(it) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read content filter rule $id: ${e.message}")
            null
        }
    }

    /**
     * Read a ContentFilterRule from a cursor row.
     */
    private fun readRuleFromCursor(cursor: android.database.Cursor): ContentFilterRule? {
        return try {
            ContentFilterRule(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_ID)),
                phrase = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_PHRASE)),
                enabled = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FILTER_ENABLED)) != 0,
                matchMode = ContentFilterMatchMode.fromString(
                    cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_MATCH_MODE))
                ),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_UPDATED_AT)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read content filter rule from cursor: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Save a new content filter rule.
     * Returns the inserted row ID, or -1 on failure.
     */
    fun saveRule(rule: ContentFilterRule): Long {
        val normalized = ContentFilterRule.normalizePhrase(rule.phrase) ?: run {
            Log.w(TAG, "Cannot save rule with blank phrase")
            return -1
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return -1
        }

        return try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COLUMN_FILTER_PHRASE, normalized)
                put(COLUMN_FILTER_ENABLED, if (rule.enabled) 1 else 0)
                put(COLUMN_FILTER_MATCH_MODE, rule.matchMode.name)
                put(COLUMN_FILTER_CREATED_AT, now)
                put(COLUMN_FILTER_UPDATED_AT, now)
            }

            val result = db.insert(TABLE_CONTENT_FILTER_RULES, null, values)
            if (result > 0) {
                Log.i(TAG, "Saved content filter rule: '$normalized' (${rule.matchMode})")
            } else {
                Log.e(TAG, "Failed to save content filter rule")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception while saving content filter rule: ${e.message}")
            -1
        }
    }

    /**
     * Update an existing content filter rule.
     * Returns true if successful, false otherwise.
     */
    fun updateRule(rule: ContentFilterRule): Boolean {
        if (rule.id <= 0) {
            Log.w(TAG, "Cannot update rule with invalid ID: ${rule.id}")
            return false
        }

        val normalized = ContentFilterRule.normalizePhrase(rule.phrase) ?: run {
            Log.w(TAG, "Cannot update rule with blank phrase")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_FILTER_PHRASE, normalized)
                put(COLUMN_FILTER_ENABLED, if (rule.enabled) 1 else 0)
                put(COLUMN_FILTER_MATCH_MODE, rule.matchMode.name)
                put(COLUMN_FILTER_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_CONTENT_FILTER_RULES,
                values,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(rule.id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Updated content filter rule ${rule.id}: '$normalized'")
            } else {
                Log.w(TAG, "No content filter rule found to update: ${rule.id}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating content filter rule: ${e.message}")
            false
        }
    }

    /**
     * Delete a content filter rule by ID.
     * Returns true if successful, false otherwise.
     */
    fun deleteRule(id: Long): Boolean {
        if (id <= 0) {
            Log.w(TAG, "Cannot delete rule with invalid ID: $id")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(
                TABLE_CONTENT_FILTER_RULES,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Deleted content filter rule $id")
            } else {
                Log.w(TAG, "No content filter rule found to delete: $id")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while deleting content filter rule: ${e.message}")
            false
        }
    }

    /**
     * Enable or disable a content filter rule by ID.
     * Returns true if successful, false otherwise.
     */
    fun setRuleEnabled(id: Long, enabled: Boolean): Boolean {
        if (id <= 0) {
            Log.w(TAG, "Cannot update rule with invalid ID: $id")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_FILTER_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_FILTER_UPDATED_AT, System.currentTimeMillis())
            }

            val result = db.update(
                TABLE_CONTENT_FILTER_RULES,
                values,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
            )

            val success = result > 0
            if (success) {
                Log.i(TAG, "Set content filter rule $id enabled=$enabled")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception while updating content filter rule enabled: ${e.message}")
            false
        }
    }

    /**
     * Get the count of content filter rules.
     * Returns 0 if database is unavailable.
     */
    fun getRuleCount(): Int {
        val db = dbHelper.getReadableDB() ?: return 0

        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CONTENT_FILTER_RULES", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count content filter rules: ${e.message}")
            0
        }
    }
}
