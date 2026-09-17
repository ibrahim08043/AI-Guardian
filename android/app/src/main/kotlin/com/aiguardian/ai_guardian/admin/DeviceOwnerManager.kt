package com.aiguardian.ai_guardian.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Device Owner manager for AI Guardian.
 *
 * Handles Device Owner status detection and Chrome managed policy application.
 * Phase L: Testing non-VPN website blocking via Chrome managed policies.
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

    /**
     * Check if AI Guardian is currently provisioned as Device Owner.
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

    /**
     * Get the current Device Owner package name (if any).
     * Returns null if no Device Owner is set.
     */
    fun getCurrentDeviceOwner(): String? {
        return try {
            // Try using the proper method instead of deprecated property
            val ownerPackage = if (dpm.isDeviceOwnerApp(PACKAGE_NAME)) {
                PACKAGE_NAME
            } else {
                // If AI Guardian is not DO, we can't easily get the current DO
                // because getDeviceOwnerComponentName is not publicly available in all APIs
                null
            }
            Log.i(TAG, "Current Device Owner: $ownerPackage")
            ownerPackage
        } catch (e: Exception) {
            Log.w(TAG, "Error getting Device Owner: ${e.message}")
            null
        }
    }

    /**
     * Attempt to apply Chrome managed policies.
     * This is the core test for Phase L.
     *
     * Tries to set ChromeURLBlocklist policy using setApplicationRestrictions.
     *
     * @param blockedDomains List of domains to block (e.g., ["youtube.com"])
     * @return true if policy was applied, false if failed or app is not Device Owner
     */
    fun applyChromeBlocklistPolicy(blockedDomains: List<String>): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "applyChromeBlocklistPolicy: Not Device Owner, cannot apply")
            return false
        }

        return try {
            val chromePackage = "com.android.chrome"

            // Create Bundle with Chrome managed policies
            // Attempt standard Chrome policy keys
            val restrictions = Bundle()

            // Try URLBlocklist-style key (may not work on Android)
            restrictions.putStringArray("URLBlocklist", blockedDomains.toTypedArray())

            // Also try alternative policy keys that Chrome might recognize
            restrictions.putStringArray("BlockedUrls", blockedDomains.toTypedArray())

            Log.i(TAG, "Applying Chrome policy with ${blockedDomains.size} blocked domains")
            blockedDomains.forEach { Log.d(TAG, "  - $it") }

            // Apply via DevicePolicyManager
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

    /**
     * Get current Chrome managed policies (restrictions).
     */
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

    /**
     * Clear Chrome managed policies.
     */
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

    /**
     * Get Device Owner status summary for UI display.
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "isDeviceOwner" to isDeviceOwner(),
            "currentDeviceOwner" to (getCurrentDeviceOwner() ?: "none"),
            "adminComponent" to adminComponent.flattenToString(),
            "canManageChromePolicy" to isDeviceOwner()
        )
    }
}
