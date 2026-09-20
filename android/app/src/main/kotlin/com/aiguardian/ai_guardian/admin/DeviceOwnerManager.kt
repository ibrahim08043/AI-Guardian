package com.aiguardian.ai_guardian.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Device Admin / Device Owner manager for AI Guardian.
 *
 * Handles:
 * - Device Administrator activation/deactivation (normal admin)
 * - Device Owner status detection (requires provisioning)
 * - Uninstall protection (requires Device Owner)
 * - Chrome managed policy application (requires Device Owner)
 */
class DeviceOwnerManager(private val context: Context) {

    companion object {
        private const val TAG = "AIGuardianDeviceOwnerMgr"
        private const val PACKAGE_NAME = "com.aiguardian.ai_guardian"
    }

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        ComponentName(context, DeviceOwnerReceiver::class.java)

    // ─────────────────────────────────────────────────────────────────────
    // Status detection
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check if AI Guardian is an active Device Administrator.
     * This is DIFFERENT from Device Owner — admin can be active without Owner.
     */
    fun isAdminActive(): Boolean {
        return try {
            val active = dpm.isAdminActive(adminComponent)
            Log.i(TAG, "isAdminActive: $active")
            active
        } catch (e: Exception) {
            Log.w(TAG, "Error checking admin status: ${e.message}")
            false
        }
    }

    /**
     * Check if AI Guardian is currently provisioned as Device Owner.
     * Device Owner is a STRONGER status than admin — it requires provisioning
     * at device setup time or via ADB.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            val isOwner = dpm.isDeviceOwnerApp(PACKAGE_NAME)
            Log.i(TAG, "isDeviceOwner: $isOwner")
            isOwner
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Device Owner status: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Device Administrator activation / deactivation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get an Intent that launches the Android system Device Admin activation screen.
     * The user must manually confirm activation on that screen.
     *
     * Returns null if admin is already active (no action needed).
     */
    fun getDeviceAdminActivationIntent(): Intent? {
        if (isAdminActive()) {
            Log.i(TAG, "getDeviceAdminActivationIntent: Already active, no intent needed")
            return null
        }

        return try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "AI Guardian needs Device Administrator to enforce app restrictions and protect your digital wellness."
                )
                // Required when launching from Application context (not Activity context)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "getDeviceAdminActivationIntent: Created activation intent")
            intent
        } catch (e: Exception) {
            Log.e(TAG, "Error creating activation intent: ${e.message}")
            null
        }
    }

    /**
     * Get an Intent that launches the Android system Device Admin deactivation screen.
     * The user must manually confirm deactivation on that screen.
     *
     * Returns null if admin is not active (nothing to deactivate).
     */
    fun getDeviceAdminDeactivationIntent(): Intent? {
        if (!isAdminActive()) {
            Log.i(TAG, "getDeviceAdminDeactivationIntent: Not active, no intent needed")
            return null
        }

        return try {
            val intent = Intent("android.app.action.DEVICE_ADMIN_DISABLE_REQUESTED").apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "getDeviceAdminDeactivationIntent: Created deactivation intent")
            intent
        } catch (e: Exception) {
            Log.e(TAG, "Error creating deactivation intent: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Uninstall protection (requires Device Owner)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Block uninstallation of the given package.
     * Requires Device Owner status. When active, the user cannot uninstall
     * the app through Settings → Apps or any other normal means.
     */
    fun applyUninstallProtection(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "applyUninstallProtection: Not Device Owner, cannot apply")
            return false
        }

        return try {
            dpm.setUninstallBlocked(adminComponent, packageName, true)
            val blocked = dpm.isUninstallBlocked(adminComponent, packageName)
            Log.i(TAG, "applyUninstallProtection($packageName): result=$blocked")
            blocked
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException applying uninstall protection: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error applying uninstall protection: ${e.message}", e)
            false
        }
    }

    /**
     * Remove uninstall protection for the given package.
     * Requires Device Owner status.
     */
    fun removeUninstallProtection(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "removeUninstallProtection: Not Device Owner, cannot remove")
            return false
        }

        return try {
            dpm.setUninstallBlocked(adminComponent, packageName, false)
            val blocked = dpm.isUninstallBlocked(adminComponent, packageName)
            Log.i(TAG, "removeUninstallProtection($packageName): stillBlocked=$blocked")
            !blocked
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException removing uninstall protection: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error removing uninstall protection: ${e.message}", e)
            false
        }
    }

    /**
     * Check if uninstall is currently blocked for the given package.
     */
    fun isUninstallBlocked(packageName: String): Boolean {
        return try {
            val blocked = dpm.isUninstallBlocked(adminComponent, packageName)
            Log.i(TAG, "isUninstallBlocked($packageName): $blocked")
            blocked
        } catch (e: Exception) {
            Log.w(TAG, "Error checking uninstall block status: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Chrome managed policies (requires Device Owner)
    // ─────────────────────────────────────────────────────────────────────

    fun applyChromeBlocklistPolicy(blockedDomains: List<String>): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "applyChromeBlocklistPolicy: Not Device Owner, cannot apply")
            return false
        }

        return try {
            val chromePackage = "com.android.chrome"
            val restrictions = Bundle()
            restrictions.putStringArray("URLBlocklist", blockedDomains.toTypedArray())
            restrictions.putStringArray("BlockedUrls", blockedDomains.toTypedArray())

            Log.i(TAG, "Applying Chrome policy with ${blockedDomains.size} blocked domains")
            blockedDomains.forEach { Log.d(TAG, "  - $it") }

            dpm.setApplicationRestrictions(adminComponent, chromePackage, restrictions)
            Log.i(TAG, "Chrome policy applied successfully")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException applying Chrome policy: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error applying Chrome policy: ${e.message}", e)
            false
        }
    }

    fun getChromePolicy(): Bundle? {
        if (!isDeviceOwner()) {
            Log.w(TAG, "getChromePolicy: Not Device Owner")
            return null
        }

        return try {
            val chromePackage = "com.android.chrome"
            val restrictions = dpm.getApplicationRestrictions(adminComponent, chromePackage)
            Log.i(TAG, "Retrieved Chrome policy: ${restrictions.size()} keys")
            restrictions
        } catch (e: Exception) {
            Log.w(TAG, "Error getting Chrome policy: ${e.message}")
            null
        }
    }

    fun clearChromePolicy(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "clearChromePolicy: Not Device Owner")
            return false
        }

        return try {
            val chromePackage = "com.android.chrome"
            dpm.setApplicationRestrictions(adminComponent, chromePackage, Bundle())
            Log.i(TAG, "Chrome policy cleared")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing Chrome policy: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Status summary
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get full admin/owner status summary for UI display.
     * All values come from actual DevicePolicyManager state.
     */
    fun getStatus(): Map<String, Any> {
        val admin = isAdminActive()
        val owner = isDeviceOwner()
        return mapOf(
            "isAdminActive" to admin,
            "isDeviceOwner" to owner,
            "isUninstallBlocked" to if (owner) isUninstallBlocked(PACKAGE_NAME) else false,
            "adminComponent" to adminComponent.flattenToString(),
            "packageName" to PACKAGE_NAME,
        )
    }
}
