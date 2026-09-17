package com.aiguardian.ai_guardian.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aiguardian.ai_guardian.policy.DomainPolicy
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DOMAIN
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DOMAIN_ENABLED
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DOMAIN_CREATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.COLUMN_DOMAIN_UPDATED_AT
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper.Companion.TABLE_DOMAINS

/**
 * Repository for domain blocking policies.
 *
 * Provides CRUD operations for domain rules stored in SQLite.
 * All queries are parameterized to prevent SQL injection.
 */
class DomainRepository(private val dbHelper: PolicyDatabaseHelper) {

    companion object {
        private const val TAG = "AIGuardianDomainRepo"
    }

    /**
     * Get all domain policies.
     */
    fun getAllDomains(): List<DomainPolicy> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(TABLE_DOMAINS, null, null, null, null, null, "$COLUMN_DOMAIN ASC")
            val domains = mutableListOf<DomainPolicy>()
            cursor.use {
                while (it.moveToNext()) {
                    val domain = it.getString(it.getColumnIndexOrThrow(COLUMN_DOMAIN))
                    val enabled = it.getInt(it.getColumnIndexOrThrow(COLUMN_DOMAIN_ENABLED)) != 0
                    val createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_DOMAIN_CREATED_AT))
                    val updatedAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_DOMAIN_UPDATED_AT))
                    domains.add(DomainPolicy(domain, enabled, createdAt, updatedAt))
                }
            }
            Log.d(TAG, "Loaded ${domains.size} domain policies")
            domains
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read domains: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get only enabled domain policies (used by VPN filter).
     */
    fun getEnabledDomains(): List<DomainPolicy> {
        val db = dbHelper.getReadableDB() ?: run {
            Log.e(TAG, "Cannot read from database")
            return emptyList()
        }

        return try {
            val cursor = db.query(
                TABLE_DOMAINS, null,
                "$COLUMN_DOMAIN_ENABLED = 1",
                null, null, null, "$COLUMN_DOMAIN ASC"
            )
            val domains = mutableListOf<DomainPolicy>()
            cursor.use {
                while (it.moveToNext()) {
                    val domain = it.getString(it.getColumnIndexOrThrow(COLUMN_DOMAIN))
                    val enabled = it.getInt(it.getColumnIndexOrThrow(COLUMN_DOMAIN_ENABLED)) != 0
                    val createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_DOMAIN_CREATED_AT))
                    val updatedAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_DOMAIN_UPDATED_AT))
                    domains.add(DomainPolicy(domain, enabled, createdAt, updatedAt))
                }
            }
            domains
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read enabled domains: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if a domain is blocked (enabled).
     */
    fun isDomainBlocked(domain: String): Boolean {
        val db = dbHelper.getReadableDB() ?: return false
        return try {
            val cursor = db.query(
                TABLE_DOMAINS, null,
                "$COLUMN_DOMAIN = ? AND $COLUMN_DOMAIN_ENABLED = 1",
                arrayOf(domain), null, null, null
            )
            cursor.use { it.count > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check domain: ${e.message}")
            false
        }
    }

    /**
     * Save a domain policy. Returns true if successful.
     */
    fun saveDomain(domainPolicy: DomainPolicy): Boolean {
        val normalized = DomainPolicy.normalize(domainPolicy.domain)
            ?: run {
                Log.w(TAG, "Invalid domain: ${domainPolicy.domain}")
                return false
            }

        // Check if exists
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val existing = db.query(
                TABLE_DOMAINS, null,
                "$COLUMN_DOMAIN = ?", arrayOf(normalized),
                null, null, null
            )
            val exists = existing.use { it.moveToFirst() }

            val values = ContentValues().apply {
                put(COLUMN_DOMAIN, normalized)
                put(COLUMN_DOMAIN_ENABLED, if (domainPolicy.enabled) 1 else 0)
                put(COLUMN_DOMAIN_UPDATED_AT, System.currentTimeMillis())
                if (!exists) {
                    put(COLUMN_DOMAIN_CREATED_AT, System.currentTimeMillis())
                }
            }

            val result = if (exists) {
                db.update(TABLE_DOMAINS, values, "$COLUMN_DOMAIN = ?", arrayOf(normalized))
            } else {
                db.insert(TABLE_DOMAINS, null, values)
            }

            val success = result.toLong() >= 0
            if (success) {
                Log.i(TAG, "Saved domain: $normalized (enabled=${domainPolicy.enabled})")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save domain: ${e.message}")
            false
        }
    }

    /**
     * Delete a domain policy. Returns true if successful.
     */
    fun deleteDomain(domain: String): Boolean {
        val normalized = DomainPolicy.normalize(domain) ?: return false
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val result = db.delete(TABLE_DOMAINS, "$COLUMN_DOMAIN = ?", arrayOf(normalized))
            val success = result > 0
            if (success) {
                Log.i(TAG, "Deleted domain: $normalized")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete domain: ${e.message}")
            false
        }
    }

    /**
     * Enable or disable a domain. Returns true if successful.
     */
    fun setDomainEnabled(domain: String, enabled: Boolean): Boolean {
        val normalized = DomainPolicy.normalize(domain) ?: return false
        val db = dbHelper.getWritableDB() ?: run {
            Log.e(TAG, "Cannot write to database")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(COLUMN_DOMAIN_ENABLED, if (enabled) 1 else 0)
                put(COLUMN_DOMAIN_UPDATED_AT, System.currentTimeMillis())
            }
            val result = db.update(TABLE_DOMAINS, values, "$COLUMN_DOMAIN = ?", arrayOf(normalized))
            val success = result > 0
            if (success) {
                Log.i(TAG, "Set domain enabled=$enabled: $normalized")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update domain: ${e.message}")
            false
        }
    }

    /**
     * Get the set of blocked domains as a HashSet for fast VPN lookup.
     */
    fun getBlockedDomainSet(): HashSet<String> {
        val enabled = getEnabledDomains()
        return HashSet(enabled.map { it.domain })
    }
}
