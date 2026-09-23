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

    init {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_INIT dbHelper=${dbHelper.hashCode()} dbName=${dbHelper.databaseName}")
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
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GETALL_NO_DB")
            return emptyList()
        }
        Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GETALL dbPath=${db.path}")

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

            Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GETALL count=${rules.size}")
            for (rule in rules) {
                Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GETALL id=${rule.id} phrase=${rule.phrase} enabled=${rule.enabled} matchMode=${rule.matchMode}")
            }
            rules
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GETALL_FAILED error=${e.message}")
            emptyList()
        }
    }

    /**
     * Get all enabled content filter rules.
     * Used by ContentFilterEngine for matching — only loads active rules.
     */
    fun getEnabledRules(): List<ContentFilterRule> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_ENABLED_NO_DB")
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

            Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_ENABLED count=${rules.size}")
            rules
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_ENABLED_FAILED error=${e.message}")
            emptyList()
        }
    }

    /**
     * Get a single rule by ID.
     * Returns null if not found or if database is unavailable.
     */
    fun getRule(id: Long): ContentFilterRule? {
        Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_RULE id=$id")
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_RULE_NO_DB")
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
                if (it.moveToFirst()) {
                    val rule = readRuleFromCursor(it)
                    Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_RULE_FOUND id=$id phrase=${rule?.phrase} enabled=${rule?.enabled}")
                    rule
                } else {
                    Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_RULE_NOT_FOUND id=$id")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_GET_RULE_EXCEPTION id=$id error=${e.message}")
            null
        }
    }

    /**
     * Read a ContentFilterRule from a cursor row.
     */
    private fun readRuleFromCursor(cursor: android.database.Cursor): ContentFilterRule? {
        return try {
            val rawId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_ID))
            val rawPhrase = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_PHRASE))
            val rawEnabledInt = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FILTER_ENABLED))
            val rawEnabledBool = rawEnabledInt != 0
            val rawMatchMode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_MATCH_MODE))
            Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_CURSOR rawId=$rawId rawPhrase=$rawPhrase rawEnabledInt=$rawEnabledInt rawEnabledBool=$rawEnabledBool rawMatchMode=$rawMatchMode")
            ContentFilterRule(
                id = rawId,
                phrase = rawPhrase,
                enabled = rawEnabledBool,
                matchMode = ContentFilterMatchMode.fromString(rawMatchMode),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILTER_UPDATED_AT)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_CURSOR_FAILED error=${e.message}")
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
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE phrase=${rule.phrase} enabled=${rule.enabled} matchMode=${rule.matchMode}")
        val normalized = ContentFilterRule.normalizePhrase(rule.phrase) ?: run {
            Log.w(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE_BLANK_PHRASE")
            return -1
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE_NO_DB")
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
            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE_RESULT phrase=$normalized id=$result")
            if (result > 0) {
                // Verify by reading back
                val verifyCursor = db.query(
                    TABLE_CONTENT_FILTER_RULES,
                    arrayOf(COLUMN_FILTER_ID, COLUMN_FILTER_PHRASE, COLUMN_FILTER_ENABLED),
                    "$COLUMN_FILTER_ID = ?",
                    arrayOf(result.toString()),
                    null, null, null,
                )
                verifyCursor.use {
                    if (it.moveToFirst()) {
                        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE_VERIFY id=${it.getLong(0)} phrase=${it.getString(1)} enabled=${it.getInt(2)}")
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_SAVE_EXCEPTION phrase=$normalized error=${e.message}")
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
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE id=$id idType=${id::class.java.simpleName}")
        if (id <= 0) {
            Log.w(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_INVALID_ID id=$id")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_NO_DB")
            return false
        }

        return try {
            // First check what exists
            val cursor = db.query(
                TABLE_CONTENT_FILTER_RULES,
                arrayOf(COLUMN_FILTER_ID, COLUMN_FILTER_PHRASE, COLUMN_FILTER_ENABLED),
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
                null, null, null,
            )
            val existsBefore = cursor.use {
                if (it.moveToFirst()) {
                    Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_BEFORE id=${it.getLong(0)} phrase=${it.getString(1)} enabled=${it.getInt(2)}")
                    true
                } else {
                    Log.w(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_BEFORE id=$id NOT_FOUND in DB")
                    false
                }
            }

            val result = db.delete(
                TABLE_CONTENT_FILTER_RULES,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
            )

            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_ROWS id=$id rowsAffected=$result")
            val success = result > 0
            if (!success) {
                Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_FAILED id=$id — rowsAffected=0")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_DELETE_EXCEPTION id=$id error=${e.message}")
            false
        }
    }

    /**
     * Enable or disable a content filter rule by ID.
     * Returns true if successful, false otherwise.
     */
    fun setRuleEnabled(id: Long, enabled: Boolean): Boolean {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE id=$id enabled=$enabled idType=${id::class.java.simpleName}")
        if (id <= 0) {
            Log.w(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_INVALID_ID id=$id")
            return false
        }

        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_NO_DB")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_FILTER_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_FILTER_UPDATED_AT, System.currentTimeMillis())
            }

            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_SQL UPDATE $TABLE_CONTENT_FILTER_RULES SET $COLUMN_FILTER_ENABLED=${if (enabled) 1 else 0} WHERE $COLUMN_FILTER_ID=? (bind=${id.toString()})")

            val result = db.update(
                TABLE_CONTENT_FILTER_RULES,
                values,
                "$COLUMN_FILTER_ID = ?",
                arrayOf(id.toString()),
            )

            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_ROWS id=$id rowsAffected=$result")
            val success = result > 0
            if (!success) {
                Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_NO_ROWS id=$id — rule not found in DB!")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG REPO_TOGGLE_EXCEPTION id=$id error=${e.message}")
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
