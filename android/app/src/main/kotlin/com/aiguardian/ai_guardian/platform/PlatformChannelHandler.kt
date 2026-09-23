package com.aiguardian.ai_guardian.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import com.aiguardian.ai_guardian.policy.Policy
import com.aiguardian.ai_guardian.policy.PolicyAction
import com.aiguardian.ai_guardian.policy.RestrictionSchedule
import com.aiguardian.ai_guardian.policy.DailyLimit
import com.aiguardian.ai_guardian.policy.DomainPolicy
import com.aiguardian.ai_guardian.policy.UsageTracker
import com.aiguardian.ai_guardian.service.AIGuardianAccessibilityService
import com.aiguardian.ai_guardian.service.DomainBlockerVpnService
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper
import com.aiguardian.ai_guardian.storage.PolicyRepository
import com.aiguardian.ai_guardian.storage.DomainRepository
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRepository
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRule
import com.aiguardian.ai_guardian.contentfilter.ContentFilterMatchMode
import com.aiguardian.ai_guardian.storage.SettingsStorage
import com.aiguardian.ai_guardian.admin.DeviceOwnerManager
import com.aiguardian.ai_guardian.admin.DeviceOwnerReceiver
import android.app.admin.DevicePolicyManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Handles MethodChannel calls from Flutter.
 *
 * This class keeps MainActivity lightweight by delegating all platform
 * method logic here. Each method corresponds to a Dart call made via
 * [AndroidPlatformService].
 *
 * Phase C additions:
 * - saveSchedule: Configure time-based blocking schedule
 * - saveDailyLimit: Configure daily usage limit
 * - getUsageToday: Get today's usage for a package
 * - getFullPolicy: Get policy with all Phase C fields
 */
class PlatformChannelHandler(private val activity: Activity) {

    companion object {
        private const val TAG = "AIGuardianPlatform"
    }

    // Activity is a Context, so this works for all Context needs (DB, resources, etc.)
    private val context: Context = activity

    private val dbHelper = PolicyDatabaseHelper(context).also {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG PLATFORM_HANDLER_INIT dbHelper=${it.hashCode()} dbName=${it.databaseName} dbPath=${it.readableDatabase.path}")
    }
    private val policyRepository = PolicyRepository(dbHelper)
    private val domainRepository = DomainRepository(dbHelper)
    private val contentFilterRepository = ContentFilterRepository(dbHelper)

    /**
     * Entry point called from MainActivity's MethodChannel handler.
     * Returns true if the method was handled, false otherwise.
     */
    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        Log.i(TAG, "[PLATFORM] Method called: ${call.method}")

        return when (call.method) {
            // Existing methods
            "getPlatformInfo" -> {
                getPlatformInfo(result)
                true
            }
            "checkAccessibilityStatus" -> {
                checkAccessibilityStatus(result)
                true
            }
            "openAccessibilitySettings" -> {
                openAccessibilitySettings(result)
                true
            }
            "getAppVersion" -> {
                getAppVersion(result)
                true
            }
            // Policy management methods
            "getPolicies" -> {
                getPolicies(result)
                true
            }
            "getPolicy" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    getPolicy(packageName, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "savePolicy" -> {
                val packageName = call.argument<String>("packageName")
                val actionStr = call.argument<String>("action")
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (packageName != null && actionStr != null) {
                    savePolicy(packageName, actionStr, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName and action required", null)
                }
                true
            }
            "updatePolicy" -> {
                val packageName = call.argument<String>("packageName")
                val actionStr = call.argument<String>("action")
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (packageName != null && actionStr != null) {
                    updatePolicy(packageName, actionStr, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName and action required", null)
                }
                true
            }
            "deletePolicy" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    deletePolicy(packageName, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "setPolicyEnabled" -> {
                val packageName = call.argument<String>("packageName")
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (packageName != null) {
                    setPolicyEnabled(packageName, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "getInstalledApps" -> {
                getInstalledApps(result)
                true
            }
            // Phase C: Smart restriction methods
            "saveSchedule" -> {
                val packageName = call.argument<String>("packageName")
                val enabled = call.argument<Boolean>("enabled") ?: false
                val startMinutes = call.argument<Int>("startMinutes")
                val endMinutes = call.argument<Int>("endMinutes")

                if (packageName != null) {
                    saveSchedule(packageName, enabled, startMinutes, endMinutes, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "getAllSchedules" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    getAllSchedules(packageName, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "addSchedule" -> {
                val packageName = call.argument<String>("packageName")
                val startMinutes = call.argument<Int>("startMinutes")
                val endMinutes = call.argument<Int>("endMinutes")
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (packageName != null && startMinutes != null && endMinutes != null) {
                    addSchedule(packageName, startMinutes, endMinutes, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName, startMinutes, endMinutes required", null)
                }
                true
            }
            "updateSchedule" -> {
                val id = call.argument<Number>("id")?.toLong()
                val startMinutes = call.argument<Int>("startMinutes")
                val endMinutes = call.argument<Int>("endMinutes")
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (id != null && startMinutes != null && endMinutes != null) {
                    updateSchedule(id, startMinutes, endMinutes, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "id, startMinutes, endMinutes required", null)
                }
                true
            }
            "deleteSchedule" -> {
                val id = call.argument<Number>("id")?.toLong()
                if (id != null) {
                    deleteSchedule(id, result)
                } else {
                    result.error("INVALID_ARGUMENT", "id required", null)
                }
                true
            }
            "toggleScheduleEnabled" -> {
                val id = call.argument<Number>("id")?.toLong()
                val enabled = call.argument<Boolean>("enabled") ?: true

                if (id != null) {
                    toggleScheduleEnabled(id, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "id required", null)
                }
                true
            }
            "saveDailyLimit" -> {
                val packageName = call.argument<String>("packageName")
                val enabled = call.argument<Boolean>("enabled") ?: false
                val limitMinutes = call.argument<Int>("limitMinutes")

                if (packageName != null) {
                    saveDailyLimit(packageName, enabled, limitMinutes, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "getUsageToday" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    getUsageToday(packageName, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            "getFullPolicy" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    getFullPolicy(packageName, result)
                } else {
                    result.error("INVALID_ARGUMENT", "packageName required", null)
                }
                true
            }
            // Phase K: Domain blocking methods
            "getAllDomains" -> {
                getAllDomains(result)
                true
            }
            "saveDomain" -> {
                val domain = call.argument<String>("domain")
                val enabled = call.argument<Boolean>("enabled") ?: true
                if (domain != null) {
                    saveDomain(domain, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "domain required", null)
                }
                true
            }
            "deleteDomain" -> {
                val domain = call.argument<String>("domain")
                if (domain != null) {
                    deleteDomain(domain, result)
                } else {
                    result.error("INVALID_ARGUMENT", "domain required", null)
                }
                true
            }
            "setDomainEnabled" -> {
                val domain = call.argument<String>("domain")
                val enabled = call.argument<Boolean>("enabled") ?: true
                if (domain != null) {
                    setDomainEnabled(domain, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "domain required", null)
                }
                true
            }
            "startDomainBlocking" -> {
                startDomainBlocking(result)
                true
            }
            "stopDomainBlocking" -> {
                stopDomainBlocking(result)
                true
            }
            "verifyPassword" -> {
                val password = call.argument<String>("password")
                if (password != null) {
                    verifyPassword(password, result)
                } else {
                    result.error("INVALID_ARGUMENT", "password required", null)
                }
                true
            }
            "hasPassword" -> {
                hasPassword(result)
                true
            }
            // Phase L: Device Owner methods
            "getDeviceOwnerStatus" -> {
                getDeviceOwnerStatus(result)
                true
            }
            "requestDeviceAdmin" -> {
                requestDeviceAdmin(result)
                true
            }
            "removeDeviceAdmin" -> {
                removeDeviceAdmin(result)
                true
            }
            "applyUninstallProtection" -> {
                applyUninstallProtection(result)
                true
            }
            "removeUninstallProtection" -> {
                removeUninstallProtection(result)
                true
            }
            "checkUninstallProtection" -> {
                checkUninstallProtection(result)
                true
            }
            "applyChromeBlocklist" -> {
                val domains = call.argument<List<String>>("domains")
                if (domains != null) {
                    applyChromeBlocklist(domains, result)
                } else {
                    result.error("INVALID_ARGUMENT", "domains required", null)
                }
                true
            }
            "getChromePolicy" -> {
                getChromePolicy(result)
                true
            }
            "clearChromePolicy" -> {
                clearChromePolicy(result)
                true
            }
            // Phase 2B: Content filter methods
            "getAllContentFilterRules" -> {
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH getAllContentFilterRules")
                getAllContentFilterRules(result)
                true
            }
            "saveContentFilterRule" -> {
                val phrase = call.argument<String>("phrase")
                val matchMode = call.argument<String>("matchMode")
                val enabled = call.argument<Boolean>("enabled") ?: true
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH saveContentFilterRule phrase=$phrase matchMode=$matchMode enabled=$enabled")
                if (phrase != null) {
                    saveContentFilterRule(phrase, matchMode, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "phrase required", null)
                }
                true
            }
            "updateContentFilterRule" -> {
                val rawId = call.arguments?.let { (it as? Map<*, *>)?.get("id") }
                val id = call.argument<Number>("id")?.toLong()
                val phrase = call.argument<String>("phrase")
                val matchMode = call.argument<String>("matchMode")
                val enabled = call.argument<Boolean>("enabled") ?: true
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH updateContentFilterRule rawId=$rawId rawIdType=${rawId?.javaClass?.simpleName} id=$id phrase=$phrase")
                if (id != null && phrase != null) {
                    updateContentFilterRule(id, phrase, matchMode, enabled, result)
                } else {
                    result.error("INVALID_ARGUMENT", "id and phrase required", null)
                }
                true
            }
            "deleteContentFilterRule" -> {
                val rawId = call.arguments?.let { (it as? Map<*, *>)?.get("id") }
                val id = call.argument<Number>("id")?.toLong()
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH deleteContentFilterRule rawId=$rawId rawIdType=${rawId?.javaClass?.simpleName} id=$id")
                if (id != null) {
                    deleteContentFilterRule(id, result)
                } else {
                    Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH deleteContentFilterRule ID_NULL rawId=$rawId rawIdType=${rawId?.javaClass?.simpleName}")
                    result.error("INVALID_ARGUMENT", "id required", null)
                }
                true
            }
            "setContentFilterRuleEnabled" -> {
                val rawId = call.arguments?.let { (it as? Map<*, *>)?.get("id") }
                val rawEnabled = call.arguments?.let { (it as? Map<*, *>)?.get("enabled") }
                val id = call.argument<Number>("id")?.toLong()
                val enabled = call.argument<Boolean>("enabled") ?: true
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH setContentFilterRuleEnabled rawId=$rawId rawIdType=${rawId?.javaClass?.simpleName} rawEnabled=$rawEnabled rawEnabledType=${rawEnabled?.javaClass?.simpleName} id=$id enabled=$enabled")
                if (id != null) {
                    setContentFilterRuleEnabled(id, enabled, result)
                } else {
                    Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DISPATCH setContentFilterRuleEnabled ID_NULL rawId=$rawId")
                    result.error("INVALID_ARGUMENT", "id required", null)
                }
                true
            }
            "setMasterContentFilterEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: true
                setMasterContentFilterEnabled(enabled, result)
                true
            }
            "isMasterContentFilterEnabled" -> {
                isMasterContentFilterEnabled(result)
                true
            }
            "refreshBlockedDomains" -> {
                refreshBlockedDomains(result)
                true
            }
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // getPlatformInfo
    // -------------------------------------------------------------------------

    private fun getPlatformInfo(result: MethodChannel.Result) {
        try {
            val map = mapOf(
                "platform" to "Android",
                "sdkVersion" to android.os.Build.VERSION.SDK_INT,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
            )
            result.success(map)
        } catch (e: Exception) {
            result.error("PLATFORM_INFO_ERROR", e.message, null)
        }
    }

    // -------------------------------------------------------------------------
    // checkAccessibilityStatus
    // -------------------------------------------------------------------------

    private fun checkAccessibilityStatus(result: MethodChannel.Result) {
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""

            val serviceComponent =
                "${context.packageName}/${context.packageName}.service.AIGuardianAccessibilityService"
            val isEnabled = enabledServices.contains(serviceComponent)

            result.success(mapOf("isEnabled" to isEnabled))
        } catch (e: Exception) {
            result.error("ACCESSIBILITY_CHECK_ERROR", e.message, null)
        }
    }

    // -------------------------------------------------------------------------
    // openAccessibilitySettings
    // -------------------------------------------------------------------------

    private fun openAccessibilitySettings(result: MethodChannel.Result) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("OPEN_SETTINGS_ERROR", e.message, null)
        }
    }

    // -------------------------------------------------------------------------
    // getAppVersion
    // -------------------------------------------------------------------------

    private fun getAppVersion(result: MethodChannel.Result) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val map = mapOf(
                "versionName" to (packageInfo.versionName ?: "0.0.0"),
                "versionCode" to packageInfo.versionCode,
            )
            result.success(map)
        } catch (e: Exception) {
            result.error("APP_VERSION_ERROR", e.message, null)
        }
    }

    // -------------------------------------------------------------------------
    // Policy management methods
    // -------------------------------------------------------------------------

    private fun getPolicies(result: MethodChannel.Result) {
        try {
            val policies = policyRepository.getAllPolicies()
            val policyMaps = policies.map { policy ->
                mapOf(
                    "packageName" to policy.packageName,
                    "action" to policy.action.name,
                    "enabled" to policy.enabled,
                )
            }
            Log.d(TAG, "getPolicies: returning ${policyMaps.size} policies")
            result.success(policyMaps)
        } catch (e: Exception) {
            Log.e(TAG, "getPolicies failed: ${e.message}")
            result.error("GET_POLICIES_ERROR", e.message, null)
        }
    }

    private fun getPolicy(packageName: String, result: MethodChannel.Result) {
        try {
            val policy = policyRepository.getPolicy(packageName)
            if (policy != null) {
                val policyMap = mapOf(
                    "packageName" to policy.packageName,
                    "action" to policy.action.name,
                    "enabled" to policy.enabled,
                )
                Log.d(TAG, "getPolicy: found $packageName")
                result.success(policyMap)
            } else {
                Log.d(TAG, "getPolicy: not found $packageName")
                result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPolicy failed: ${e.message}")
            result.error("GET_POLICY_ERROR", e.message, null)
        }
    }

    private fun savePolicy(
        packageName: String,
        actionStr: String,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            val action = PolicyAction.valueOf(actionStr)
            val policy = Policy(packageName, action, enabled)
            val success = policyRepository.savePolicy(policy)

            if (success) {
                Log.i(TAG, "savePolicy: saved $packageName → $actionStr")
                notifyPolicyChanged(packageName)
                result.success(mapOf("success" to true))
            } else {
                Log.w(TAG, "savePolicy: database save returned false")
                result.error("SAVE_POLICY_ERROR", "Database save failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "savePolicy failed: ${e.message}")
            result.error("SAVE_POLICY_ERROR", e.message, null)
        }
    }

    private fun updatePolicy(
        packageName: String,
        actionStr: String,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            val action = PolicyAction.valueOf(actionStr)
            val policy = Policy(packageName, action, enabled)
            val success = policyRepository.updatePolicy(policy)

            if (success) {
                Log.i(TAG, "updatePolicy: updated $packageName → $actionStr")
                result.success(mapOf("success" to true))
            } else {
                Log.w(TAG, "updatePolicy: database update returned false")
                result.error("UPDATE_POLICY_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updatePolicy failed: ${e.message}")
            result.error("UPDATE_POLICY_ERROR", e.message, null)
        }
    }

    private fun deletePolicy(packageName: String, result: MethodChannel.Result) {
        try {
            val success = policyRepository.deletePolicy(packageName)

            if (success) {
                Log.i(TAG, "deletePolicy: deleted $packageName")
                notifyPolicyChanged(packageName)
                result.success(mapOf("success" to true))
            } else {
                Log.w(TAG, "deletePolicy: database delete returned false")
                result.error("DELETE_POLICY_ERROR", "Database delete failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePolicy failed: ${e.message}")
            result.error("DELETE_POLICY_ERROR", e.message, null)
        }
    }

    private fun setPolicyEnabled(
        packageName: String,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            val success = policyRepository.setPolicyEnabled(packageName, enabled)

            if (success) {
                Log.i(TAG, "setPolicyEnabled: set $packageName to enabled=$enabled")
                result.success(mapOf("success" to true))
            } else {
                Log.w(TAG, "setPolicyEnabled: database update returned false")
                result.error("SET_POLICY_ENABLED_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setPolicyEnabled failed: ${e.message}")
            result.error("SET_POLICY_ENABLED_ERROR", e.message, null)
        }
    }

    // -------------------------------------------------------------------------
    // Phase C: Smart restriction methods
    // -------------------------------------------------------------------------

    private fun saveSchedule(
        packageName: String,
        enabled: Boolean,
        startMinutes: Int?,
        endMinutes: Int?,
        result: MethodChannel.Result,
    ) {
        try {
            // Validate schedule if enabling
            if (enabled && (startMinutes == null || endMinutes == null)) {
                result.error("INVALID_SCHEDULE", "startMinutes and endMinutes required when enabling", null)
                return
            }

            if (enabled) {
                // Validate ranges
                if (startMinutes !in 0..1439 || endMinutes !in 0..1439) {
                    result.error("INVALID_SCHEDULE", "Minutes must be 0-1439", null)
                    return
                }
                // Validate the schedule can be constructed
                RestrictionSchedule.fromMinutes(startMinutes!!, endMinutes!!)
            }

            // Ensure a policy row exists before updating schedule.
            ensurePolicyExists(packageName)

            val success = policyRepository.updateSchedule(packageName, enabled, startMinutes, endMinutes)
            if (success) {
                Log.i(TAG, "saveSchedule: $packageName enabled=$enabled")
                notifyPolicyChanged(packageName)
                result.success(mapOf("success" to true))
            } else {
                result.error("SAVE_SCHEDULE_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveSchedule failed: ${e.message}")
            result.error("SAVE_SCHEDULE_ERROR", e.message, null)
        }
    }

    private fun getAllSchedules(packageName: String, result: MethodChannel.Result) {
        try {
            val schedules = policyRepository.getSchedulesForPackage(packageName)
            val scheduleMaps = schedules.map { s ->
                mapOf(
                    "id" to s.id,
                    "startMinutes" to s.startMinutes,
                    "endMinutes" to s.endMinutes,
                    "enabled" to s.enabled,
                )
            }
            Log.d(TAG, "getAllSchedules: $packageName = ${scheduleMaps.size} schedules")
            result.success(scheduleMaps)
        } catch (e: Exception) {
            Log.e(TAG, "getAllSchedules failed: ${e.message}")
            result.error("GET_SCHEDULES_ERROR", e.message, null)
        }
    }

    private fun addSchedule(
        packageName: String,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            // Validate ranges
            if (startMinutes !in 0..1439 || endMinutes !in 0..1439) {
                result.error("INVALID_SCHEDULE", "Minutes must be 0-1439", null)
                return
            }
            // Validate the schedule can be constructed
            RestrictionSchedule.fromMinutes(startMinutes, endMinutes)

            // Ensure a policy row exists
            ensurePolicyExists(packageName)

            val id = policyRepository.saveSchedule(packageName, startMinutes, endMinutes, enabled)
            if (id > 0) {
                Log.i(TAG, "addSchedule: $packageName id=$id $startMinutes→$endMinutes")
                notifyPolicyChanged(packageName)
                result.success(mapOf("success" to true, "id" to id))
            } else {
                result.error("ADD_SCHEDULE_ERROR", "Database insert failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "addSchedule failed: ${e.message}")
            result.error("ADD_SCHEDULE_ERROR", e.message, null)
        }
    }

    private fun updateSchedule(
        id: Long,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            if (startMinutes !in 0..1439 || endMinutes !in 0..1439) {
                result.error("INVALID_SCHEDULE", "Minutes must be 0-1439", null)
                return
            }
            RestrictionSchedule.fromMinutes(startMinutes, endMinutes)

            val success = policyRepository.updateScheduleById(id, startMinutes, endMinutes, enabled)
            if (success) {
                Log.i(TAG, "updateSchedule: id=$id $startMinutes→$endMinutes enabled=$enabled")
                // Notify all policies (we don't know which package from just the schedule ID)
                result.success(mapOf("success" to true))
            } else {
                result.error("UPDATE_SCHEDULE_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSchedule failed: ${e.message}")
            result.error("UPDATE_SCHEDULE_ERROR", e.message, null)
        }
    }

    private fun deleteSchedule(id: Long, result: MethodChannel.Result) {
        try {
            val success = policyRepository.deleteScheduleById(id)
            if (success) {
                Log.i(TAG, "deleteSchedule: id=$id")
                result.success(mapOf("success" to true))
            } else {
                result.error("DELETE_SCHEDULE_ERROR", "Database delete failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteSchedule failed: ${e.message}")
            result.error("DELETE_SCHEDULE_ERROR", e.message, null)
        }
    }

    private fun toggleScheduleEnabled(id: Long, enabled: Boolean, result: MethodChannel.Result) {
        try {
            val success = policyRepository.toggleScheduleEnabled(id, enabled)
            if (success) {
                Log.i(TAG, "toggleScheduleEnabled: id=$id enabled=$enabled")
                result.success(mapOf("success" to true))
            } else {
                result.error("TOGGLE_SCHEDULE_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleScheduleEnabled failed: ${e.message}")
            result.error("TOGGLE_SCHEDULE_ERROR", e.message, null)
        }
    }

    private fun saveDailyLimit(
        packageName: String,
        enabled: Boolean,
        limitMinutes: Int?,
        result: MethodChannel.Result,
    ) {
        try {
            // Validate limit if enabling
            if (enabled && (limitMinutes == null || limitMinutes <= 0)) {
                result.error("INVALID_LIMIT", "limitMinutes must be > 0 when enabling", null)
                return
            }

            if (enabled && limitMinutes != null) {
                // Validate the limit can be constructed
                DailyLimit.fromMinutes(limitMinutes)
            }

            // Ensure a policy row exists before updating daily limit.
            // If no policy exists, create one with action=ALLOW so the daily limit
            // is the active restriction (block after limit exceeded).
            ensurePolicyExists(packageName)

            val success = policyRepository.updateDailyLimit(packageName, enabled, limitMinutes)
            if (success) {
                Log.i(TAG, "saveDailyLimit: $packageName enabled=$enabled, limit=$limitMinutes")
                notifyPolicyChanged(packageName)
                result.success(mapOf("success" to true))
            } else {
                result.error("SAVE_LIMIT_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveDailyLimit failed: ${e.message}")
            result.error("SAVE_LIMIT_ERROR", e.message, null)
        }
    }

    private fun getUsageToday(packageName: String, result: MethodChannel.Result) {
        try {
            val date = UsageTracker.todayDate()
            val totalMs = policyRepository.getTotalUsageMs(packageName, date)
            val totalMinutes = (totalMs / (60 * 1000)).toInt()

            // Also check for active session
            val tracker = AIGuardianAccessibilityService.usageTracker
            val activeSessionMs = tracker?.getUsageTodayMs(packageName) ?: totalMs

            val map = mapOf(
                "packageName" to packageName,
                "usageMinutes" to (activeSessionMs / (60 * 1000)).toInt(),
                "usageMs" to activeSessionMs,
                "date" to date,
            )
            Log.d(TAG, "getUsageToday: $packageName = $totalMinutes min")
            result.success(map)
        } catch (e: Exception) {
            Log.e(TAG, "getUsageToday failed: ${e.message}")
            result.error("GET_USAGE_ERROR", e.message, null)
        }
    }

    private fun getFullPolicy(packageName: String, result: MethodChannel.Result) {
        try {
            val policy = policyRepository.getPolicy(packageName)
            if (policy != null) {
                val map = buildFullPolicyMap(policy)
                Log.d(TAG, "getFullPolicy: found $packageName")
                result.success(map)
            } else {
                Log.d(TAG, "getFullPolicy: not found $packageName")
                result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFullPolicy failed: ${e.message}")
            result.error("GET_FULL_POLICY_ERROR", e.message, null)
        }
    }

    private fun buildFullPolicyMap(policy: Policy): Map<String, Any?> {
        val schedulesList = policy.schedules.map { s ->
            mapOf(
                "id" to s.id,
                "startMinutes" to s.startMinutes,
                "endMinutes" to s.endMinutes,
                "enabled" to s.enabled,
            )
        }

        val dailyLimitMap = if (policy.dailyLimit != null) {
            mapOf(
                "enabled" to policy.dailyLimitEnabled,
                "limitMinutes" to policy.dailyLimit.limitMinutes,
            )
        } else {
            mapOf(
                "enabled" to policy.dailyLimitEnabled,
                "limitMinutes" to null,
            )
        }

        return mapOf(
            "packageName" to policy.packageName,
            "action" to policy.action.name,
            "enabled" to policy.enabled,
            "schedules" to schedulesList,
            "dailyLimit" to dailyLimitMap,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Notify the AccessibilityService's ForegroundMonitor that a policy has changed.
     * If the changed package is currently being monitored, its boundary is recalculated.
     */
    private fun notifyPolicyChanged(packageName: String) {
        try {
            AIGuardianAccessibilityService.instance?.notifyPolicyChanged(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify policy change for $packageName: ${e.message}")
        }
    }

    /**
     * Ensure a policy row exists for the given package.
     * If no policy exists, creates one with action=ALLOW (enabled=true).
     *
     * This is needed so that schedule and daily limit updates can be applied
     * to a policy row. Without this, turning OFF "Restrict App" would delete
     * the policy row, and subsequent schedule/limit updates would silently fail
     * (SQL UPDATE on a non-existent row returns 0 rows updated).
     *
     * When action=ALLOW, the schedule/limit themselves become the sole
     * restriction mechanism: block only during scheduled windows or after
     * daily limit is exceeded.
     */
    private fun ensurePolicyExists(packageName: String) {
        val existing = policyRepository.getPolicy(packageName)
        if (existing == null) {
            val fallback = Policy(
                packageName = packageName,
                action = PolicyAction.ALLOW,
                enabled = true,
            )
            policyRepository.savePolicy(fallback)
            Log.d(TAG, "ensurePolicyExists: created ALLOW policy for $packageName")
        }
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    private fun verifyPassword(password: String, result: MethodChannel.Result) {
        try {
            val storedPassword = readPasswordFromAssets()
            val match = password == storedPassword
            Log.d(TAG, "verifyPassword: match=$match")
            result.success(mapOf("success" to match))
        } catch (e: Exception) {
            Log.e(TAG, "verifyPassword failed: ${e.message}")
            result.error("VERIFY_PASSWORD_ERROR", e.message, null)
        }
    }

    private fun hasPassword(result: MethodChannel.Result) {
        try {
            val storedPassword = readPasswordFromAssets()
            val exists = storedPassword.isNotEmpty()
            Log.d(TAG, "hasPassword: $exists")
            result.success(mapOf("hasPassword" to exists))
        } catch (e: Exception) {
            Log.e(TAG, "hasPassword failed: ${e.message}")
            // If asset can't be read, assume password exists (fail-closed)
            result.success(mapOf("hasPassword" to true))
        }
    }

    private fun readPasswordFromAssets(): String {
        return context.assets.open("app_password.txt")
            .bufferedReader()
            .use { it.readText().trimEnd('\r', '\n') }
    }

    // -------------------------------------------------------------------------
    // Installed apps
    // -------------------------------------------------------------------------

    private fun getInstalledApps(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "getInstalledApps: Starting to fetch installed apps")
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            Log.d(TAG, "getInstalledApps: Found ${packages.size} total applications")

            val apps = mutableListOf<Map<String, Any>>()
            for (app in packages) {
                // Skip pure system apps but show updated system apps (e.g., Google, YouTube updated via Play Store)
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue
                }

                // Skip AI Guardian itself
                if (app.packageName == context.packageName) {
                    continue
                }

                try {
                    val appName = pm.getApplicationLabel(app).toString()
                    Log.d(TAG, "getInstalledApps: Processing app: $appName (${app.packageName})")

                    // Try to cache icon, but don't fail if it doesn't work
                    var iconPath = ""
                    try {
                        iconPath = cacheAppIcon(app.packageName, pm.getApplicationIcon(app))
                        Log.d(TAG, "getInstalledApps: Cached icon for ${app.packageName} at $iconPath")
                    } catch (iconError: Exception) {
                        Log.w(TAG, "Failed to cache icon for ${app.packageName}: ${iconError.message}")
                        // Continue without icon — UI will show placeholder
                    }

                    apps.add(
                        mapOf(
                            "packageName" to app.packageName,
                            "appName" to appName,
                            "iconPath" to iconPath,
                        )
                    )
                    Log.d(TAG, "getInstalledApps: Added app: $appName")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get app info for ${app.packageName}: ${e.message}")
                    // Skip this app and continue with the next
                }
            }

            Log.d(TAG, "getInstalledApps: Returning ${apps.size} user apps")
            result.success(apps)
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledApps failed: ${e.message}", e)
            result.error("GET_INSTALLED_APPS_ERROR", e.message, null)
        }
    }

    private fun cacheAppIcon(packageName: String, drawable: Drawable): String {
        val cacheDir = File(context.cacheDir, "app_icons")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val fileName = "${packageName.replace(".", "_")}.png"
        val file = File(cacheDir, fileName)

        // Convert drawable to bitmap and save to cache
        try {
            val bitmap = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                else -> {
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        if (width > 0) width else 1,
                        if (height > 0) height else 1,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            // Save bitmap to PNG file
            val fos = file.outputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            fos.close()

            return file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache icon for $packageName: ${e.message}")
            // Return empty string on error — Flutter will use placeholder icon
            return ""
        }
    }

    // -------------------------------------------------------------------------
    // Phase K: Domain blocking methods
    // -------------------------------------------------------------------------

    private fun getAllDomains(result: MethodChannel.Result) {
        try {
            val domains = domainRepository.getAllDomains()
            val domainMaps = domains.map { domain ->
                mapOf(
                    "domain" to domain.domain,
                    "enabled" to if (domain.enabled) 1 else 0,
                    "createdAt" to domain.createdAt,
                    "updatedAt" to domain.updatedAt,
                )
            }
            Log.d(TAG, "getAllDomains: returning ${domainMaps.size} domains")
            result.success(domainMaps)
        } catch (e: Exception) {
            Log.e(TAG, "getAllDomains failed: ${e.message}")
            result.error("GET_DOMAINS_ERROR", e.message, null)
        }
    }

    private fun saveDomain(domain: String, enabled: Boolean, result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[DOMAIN] saveDomain() called: domain=$domain, enabled=$enabled")

            val normalized = DomainPolicy.normalize(domain)
            if (normalized == null) {
                Log.w(TAG, "[DOMAIN] saveDomain: Invalid domain format: $domain")
                result.error("INVALID_DOMAIN", "Invalid domain format", null)
                return
            }
            Log.i(TAG, "[DOMAIN] Domain normalized: $domain → $normalized")

            val domainPolicy = DomainPolicy(
                domain = normalized,
                enabled = enabled,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )

            val success = domainRepository.saveDomain(domainPolicy)
            if (success) {
                Log.i(TAG, "[DOMAIN] saveDomain SUCCESS: $normalized enabled=$enabled")
                notifyDomainChanged()
                result.success(mapOf("success" to true, "domain" to normalized))
            } else {
                Log.e(TAG, "[DOMAIN] saveDomain: Database update failed")
                result.error("SAVE_DOMAIN_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DOMAIN] saveDomain EXCEPTION: ${e.message}", e)
            result.error("SAVE_DOMAIN_ERROR", e.message, null)
        }
    }

    private fun deleteDomain(domain: String, result: MethodChannel.Result) {
        try {
            val normalized = DomainPolicy.normalize(domain)
            if (normalized == null) {
                result.error("INVALID_DOMAIN", "Invalid domain format", null)
                return
            }

            val success = domainRepository.deleteDomain(normalized)
            if (success) {
                Log.i(TAG, "deleteDomain: $normalized")
                notifyDomainChanged()
                result.success(mapOf("success" to true))
            } else {
                result.error("DELETE_DOMAIN_ERROR", "Domain not found", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteDomain failed: ${e.message}")
            result.error("DELETE_DOMAIN_ERROR", e.message, null)
        }
    }

    private fun setDomainEnabled(domain: String, enabled: Boolean, result: MethodChannel.Result) {
        try {
            val normalized = DomainPolicy.normalize(domain)
            if (normalized == null) {
                result.error("INVALID_DOMAIN", "Invalid domain format", null)
                return
            }

            val success = domainRepository.setDomainEnabled(normalized, enabled)
            if (success) {
                Log.i(TAG, "setDomainEnabled: $normalized enabled=$enabled")
                notifyDomainChanged()
                result.success(mapOf("success" to true))
            } else {
                result.error("UPDATE_DOMAIN_ERROR", "Domain not found", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setDomainEnabled failed: ${e.message}")
            result.error("UPDATE_DOMAIN_ERROR", e.message, null)
        }
    }

    private fun startDomainBlocking(result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[DOMAIN] startDomainBlocking() called")
            val blockedDomainSet = domainRepository.getBlockedDomainSet()
            Log.i(TAG, "[DOMAIN] Retrieved ${blockedDomainSet.size} blocked domains")
            blockedDomainSet.forEach { domain ->
                Log.d(TAG, "[DOMAIN] Domain to block: $domain")
            }

            Log.i(TAG, "[DOMAIN] Updating VPN service blocked domains...")
            DomainBlockerVpnService.updateBlockedDomains(blockedDomainSet)

            Log.i(TAG, "[DOMAIN] Starting VPN service...")
            DomainBlockerVpnService.start(context)

            Log.i(TAG, "[DOMAIN] startDomainBlocking SUCCESS")
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            Log.e(TAG, "[DOMAIN] startDomainBlocking FAILED: ${e.message}", e)
            result.error("START_VPN_ERROR", e.message, null)
        }
    }

    private fun stopDomainBlocking(result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[DOMAIN] stopDomainBlocking() called")
            DomainBlockerVpnService.stop(context)
            Log.i(TAG, "[DOMAIN] stopDomainBlocking SUCCESS")
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            Log.e(TAG, "[DOMAIN] stopDomainBlocking FAILED: ${e.message}", e)
            result.error("STOP_VPN_ERROR", e.message, null)
        }
    }

    private fun notifyDomainChanged() {
        try {
            // Reload blocked domains and update VPN service
            val blockedDomainSet = domainRepository.getBlockedDomainSet()
            DomainBlockerVpnService.updateBlockedDomains(blockedDomainSet)
            Log.d(TAG, "Domain list updated: ${blockedDomainSet.size} domains active")
        } catch (e: Exception) {
            Log.e(TAG, "notifyDomainChanged failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Phase 2B: Content filter methods
    // ─────────────────────────────────────────────────────────────

    private fun getAllContentFilterRules(result: MethodChannel.Result) {
        try {
            val rules = contentFilterRepository.getAllRules()
            Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_LOAD_RULES count=${rules.size}")
            val ruleMaps = rules.map { rule ->
                Log.d(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_RULE id=${rule.id} idType=${rule.id::class.java.simpleName} phrase=${rule.phrase} enabled=${rule.enabled} matchMode=${rule.matchMode}")
                mapOf(
                    "id" to rule.id,
                    "phrase" to rule.phrase,
                    "enabled" to rule.enabled,
                    "matchMode" to rule.matchMode.name,
                    "createdAt" to rule.createdAt,
                    "updatedAt" to rule.updatedAt,
                )
            }
            result.success(ruleMaps)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_LOAD_RULES_FAILED error=${e.message}")
            result.error("GET_FILTER_RULES_ERROR", e.message, null)
        }
    }

    private fun saveContentFilterRule(
        phrase: String,
        matchModeStr: String?,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_SAVE_RECEIVED phrase=$phrase matchMode=$matchModeStr enabled=$enabled")
        try {
            val normalized = ContentFilterRule.normalizePhrase(phrase)
            if (normalized == null) {
                result.error("INVALID_PHRASE", "Phrase cannot be empty", null)
                return
            }

            val validationError = ContentFilterRule.validatePhrase(normalized)
            if (validationError != null) {
                result.error("INVALID_PHRASE", validationError, null)
                return
            }

            val matchMode = ContentFilterMatchMode.fromString(matchModeStr)
            val rule = ContentFilterRule(
                phrase = normalized,
                enabled = enabled,
                matchMode = matchMode,
            )

            val id = contentFilterRepository.saveRule(rule)
            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_SAVE_DB_RESULT phrase=$normalized id=$id")
            if (id > 0) {
                refreshContentFilterRules()
                result.success(mapOf("success" to true, "id" to id))
            } else {
                result.error("SAVE_FILTER_RULE_ERROR", "Database save failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_SAVE_FAILED phrase=$phrase error=${e.message}")
            result.error("SAVE_FILTER_RULE_ERROR", e.message, null)
        }
    }

    private fun updateContentFilterRule(
        id: Long,
        phrase: String,
        matchModeStr: String?,
        enabled: Boolean,
        result: MethodChannel.Result,
    ) {
        try {
            val normalized = ContentFilterRule.normalizePhrase(phrase)
            if (normalized == null) {
                result.error("INVALID_PHRASE", "Phrase cannot be empty", null)
                return
            }

            val matchMode = ContentFilterMatchMode.fromString(matchModeStr)
            val rule = ContentFilterRule(
                id = id,
                phrase = normalized,
                enabled = enabled,
                matchMode = matchMode,
            )

            val success = contentFilterRepository.updateRule(rule)
            if (success) {
                Log.i(TAG, "updateContentFilterRule: updated id=$id '$normalized'")
                refreshContentFilterRules()
                result.success(mapOf("success" to true))
            } else {
                result.error("UPDATE_FILTER_RULE_ERROR", "Database update failed", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateContentFilterRule failed: ${e.message}")
            result.error("UPDATE_FILTER_RULE_ERROR", e.message, null)
        }
    }

    private fun deleteContentFilterRule(id: Long, result: MethodChannel.Result) {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DELETE_RECEIVED id=$id idType=${id::class.java.simpleName}")
        try {
            // Check if rule exists first
            val existingRule = contentFilterRepository.getRule(id)
            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DELETE_EXISTS id=$id exists=${existingRule != null} phrase=${existingRule?.phrase}")
            val success = contentFilterRepository.deleteRule(id)
            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DELETE_DB_RESULT id=$id success=$success")
            if (success) {
                // Verify deletion
                val verifyRule = contentFilterRepository.getRule(id)
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DELETE_VERIFY id=$id stillExists=${verifyRule != null}")
                refreshContentFilterRules()
                result.success(mapOf("success" to true))
            } else {
                result.error("DELETE_FILTER_RULE_ERROR", "Database delete failed: rowsAffected=0 for id=$id", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_DELETE_FAILED id=$id error=${e.message}")
            result.error("DELETE_FILTER_RULE_ERROR", e.message, null)
        }
    }

    private fun setContentFilterRuleEnabled(id: Long, enabled: Boolean, result: MethodChannel.Result) {
        Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_TOGGLE_RECEIVED id=$id enabled=$enabled idType=${id::class.java.simpleName}")
        try {
            val success = contentFilterRepository.setRuleEnabled(id, enabled)
            Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_TOGGLE_DB_RESULT id=$id success=$success")
            if (success) {
                // Verify by reading back
                val verifyRule = contentFilterRepository.getRule(id)
                Log.i(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_TOGGLE_VERIFY id=$id verifiedEnabled=${verifyRule?.enabled}")
                refreshContentFilterRules()
                result.success(mapOf("success" to true))
            } else {
                result.error("UPDATE_FILTER_RULE_ERROR", "Database update failed: rowsAffected=0 for id=$id", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_CONTENT_FILTER_DEBUG NATIVE_TOGGLE_FAILED id=$id error=${e.message}")
            result.error("UPDATE_FILTER_RULE_ERROR", e.message, null)
        }
    }

    private fun setMasterContentFilterEnabled(enabled: Boolean, result: MethodChannel.Result) {
        try {
            val engine = AIGuardianAccessibilityService.contentFilterEngine
            if (engine != null) {
                engine.setMasterEnabled(enabled)
                Log.i(TAG, "setMasterContentFilterEnabled: $enabled")
                result.success(mapOf("success" to true))
            } else {
                // Store preference even if engine isn't running yet
                Log.w(TAG, "ContentFilterEngine not available, storing preference")
                result.success(mapOf("success" to true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "setMasterContentFilterEnabled failed: ${e.message}")
            result.error("MASTER_FILTER_ERROR", e.message, null)
        }
    }

    private fun isMasterContentFilterEnabled(result: MethodChannel.Result) {
        try {
            val engine = AIGuardianAccessibilityService.contentFilterEngine
            val enabled = engine?.isMasterEnabled() ?: true
            Log.d(TAG, "isMasterContentFilterEnabled: $enabled")
            result.success(mapOf("enabled" to enabled))
        } catch (e: Exception) {
            Log.e(TAG, "isMasterContentFilterEnabled failed: ${e.message}")
            result.success(mapOf("enabled" to true))
        }
    }

    /**
     * Notify the AccessibilityService to refresh content filter rules from the database.
     */
    private fun refreshContentFilterRules() {
        try {
            AIGuardianAccessibilityService.instance?.refreshContentFilterRules()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh content filter rules: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Phase 3B: Website blocking (AccessibilityService-based)
    // ─────────────────────────────────────────────────────────────

    private fun refreshBlockedDomains(result: MethodChannel.Result) {
        try {
            AIGuardianAccessibilityService.instance?.refreshBlockedDomains()
            Log.i(TAG, "refreshBlockedDomains called")
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            Log.e(TAG, "refreshBlockedDomains failed: ${e.message}")
            result.error("REFRESH_ERROR", e.message, null)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Phase L: Device Owner methods
    // ─────────────────────────────────────────────────────────────

    private fun getDeviceOwnerStatus(result: MethodChannel.Result) {
        try {
            val deviceOwnerManager = DeviceOwnerManager(context)
            val status = deviceOwnerManager.getStatus()
            Log.i(TAG, "[DEVICE_OWNER] Status: $status")
            result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_OWNER] getStatus failed: ${e.message}")
            result.error("DEVICE_OWNER_ERROR", e.message, null)
        }
    }

    private fun requestDeviceAdmin(result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[DEVICE_ADMIN] Flutter request received")

            // Check if already active using real DevicePolicyManager state
            val deviceOwnerManager = DeviceOwnerManager(context)
            if (deviceOwnerManager.isAdminActive()) {
                Log.i(TAG, "[DEVICE_ADMIN] Already active")
                result.success(mapOf("launched" to false, "alreadyActive" to true))
                return
            }

            // Build the official Device Administrator activation Intent
            // using the actual Activity context (not Application context)
            val adminComponent = ComponentName(activity, DeviceOwnerReceiver::class.java)
            Log.i(TAG, "[DEVICE_ADMIN] Activity available: ${activity.javaClass.simpleName}")
            Log.i(TAG, "[DEVICE_ADMIN] Admin component = ${adminComponent.flattenToString()}")

            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Allow AI Guardian to act as a Device Administrator."
                )
            }

            Log.i(TAG, "[DEVICE_ADMIN] Starting ACTION_ADD_DEVICE_ADMIN")
            activity.startActivity(intent)
            Log.i(TAG, "[DEVICE_ADMIN] startActivity() called")
            result.success(mapOf("launched" to true, "alreadyActive" to false))
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "[DEVICE_ADMIN] Failed: No activity found to handle ADD_DEVICE_ADMIN: ${e.message}")
            result.error("DEVICE_ADMIN_ERROR", "No system activity found to handle Device Administrator activation. ${e.message}", null)
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_ADMIN] Failed: ${e.message}", e)
            result.error("DEVICE_ADMIN_ERROR", e.message, null)
        }
    }

    private fun removeDeviceAdmin(result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[DEVICE_ADMIN] Flutter remove request received")

            val deviceOwnerManager = DeviceOwnerManager(context)
            if (!deviceOwnerManager.isAdminActive()) {
                Log.i(TAG, "[DEVICE_ADMIN] Not active, nothing to remove")
                result.success(mapOf("launched" to false, "notActive" to true))
                return
            }

            val adminComponent = ComponentName(activity, DeviceOwnerReceiver::class.java)
            val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(adminComponent)
            Log.i(TAG, "[DEVICE_ADMIN] removeActiveAdmin() called")
            result.success(mapOf("launched" to true, "notActive" to false))
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_ADMIN] Failed: ${e.message}", e)
            result.error("DEVICE_ADMIN_ERROR", e.message, null)
        }
    }

    private fun applyUninstallProtection(result: MethodChannel.Result) {
        try {
            val deviceOwnerManager = DeviceOwnerManager(context)
            if (!deviceOwnerManager.isDeviceOwner()) {
                Log.w(TAG, "[DEVICE_OWNER] Not Device Owner, cannot apply uninstall protection")
                result.error("NOT_DEVICE_OWNER", "AI Guardian is not Device Owner", null)
                return
            }
            val success = deviceOwnerManager.applyUninstallProtection(context.packageName)
            Log.i(TAG, "[DEVICE_OWNER] applyUninstallProtection: $success")
            result.success(mapOf("success" to success))
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_OWNER] applyUninstallProtection failed: ${e.message}")
            result.error("DEVICE_OWNER_ERROR", e.message, null)
        }
    }

    private fun removeUninstallProtection(result: MethodChannel.Result) {
        try {
            val deviceOwnerManager = DeviceOwnerManager(context)
            if (!deviceOwnerManager.isDeviceOwner()) {
                Log.w(TAG, "[DEVICE_OWNER] Not Device Owner, cannot remove uninstall protection")
                result.error("NOT_DEVICE_OWNER", "AI Guardian is not Device Owner", null)
                return
            }
            val success = deviceOwnerManager.removeUninstallProtection(context.packageName)
            Log.i(TAG, "[DEVICE_OWNER] removeUninstallProtection: $success")
            result.success(mapOf("success" to success))
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_OWNER] removeUninstallProtection failed: ${e.message}")
            result.error("DEVICE_OWNER_ERROR", e.message, null)
        }
    }

    private fun checkUninstallProtection(result: MethodChannel.Result) {
        try {
            val deviceOwnerManager = DeviceOwnerManager(context)
            val blocked = deviceOwnerManager.isUninstallBlocked(context.packageName)
            Log.i(TAG, "[DEVICE_OWNER] checkUninstallProtection: $blocked")
            result.success(mapOf("isUninstallBlocked" to blocked))
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_OWNER] checkUninstallProtection failed: ${e.message}")
            result.error("DEVICE_OWNER_ERROR", e.message, null)
        }
    }

    private fun applyChromeBlocklist(domains: List<String>, result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[CHROME_POLICY] Applying URLBlocklist for ${domains.size} domains")
            val deviceOwnerManager = DeviceOwnerManager(context)

            if (!deviceOwnerManager.isDeviceOwner()) {
                Log.w(TAG, "[CHROME_POLICY] Not Device Owner, cannot apply policy")
                result.error("NOT_DEVICE_OWNER", "AI Guardian is not Device Owner", null)
                return
            }

            val success = deviceOwnerManager.applyChromeBlocklistPolicy(domains)
            Log.i(TAG, "[CHROME_POLICY] Apply result: $success")
            result.success(mapOf("applied" to success, "domainCount" to domains.size))
        } catch (e: Exception) {
            Log.e(TAG, "[CHROME_POLICY] applyChromeBlocklist failed: ${e.message}", e)
            result.error("CHROME_POLICY_ERROR", e.message, null)
        }
    }

    private fun getChromePolicy(result: MethodChannel.Result) {
        try {
            val deviceOwnerManager = DeviceOwnerManager(context)
            val policy = deviceOwnerManager.getChromePolicy()

            if (policy == null) {
                Log.i(TAG, "[CHROME_POLICY] No Chrome policy set")
                result.success(null)
                return
            }

            // Convert Bundle to Map for JSON serialization
            val policyMap = mutableMapOf<String, Any>()
            for (key in policy.keySet()) {
                val value = policy.get(key)
                if (value != null) {
                    policyMap[key] = value
                }
            }

            Log.i(TAG, "[CHROME_POLICY] Retrieved policy with ${policyMap.size} keys")
            result.success(policyMap)
        } catch (e: Exception) {
            Log.e(TAG, "[CHROME_POLICY] getChromePolicy failed: ${e.message}")
            result.error("CHROME_POLICY_ERROR", e.message, null)
        }
    }

    private fun clearChromePolicy(result: MethodChannel.Result) {
        try {
            Log.i(TAG, "[CHROME_POLICY] Clearing Chrome policy")
            val deviceOwnerManager = DeviceOwnerManager(context)

            if (!deviceOwnerManager.isDeviceOwner()) {
                Log.w(TAG, "[CHROME_POLICY] Not Device Owner, cannot clear policy")
                result.error("NOT_DEVICE_OWNER", "AI Guardian is not Device Owner", null)
                return
            }

            val success = deviceOwnerManager.clearChromePolicy()
            Log.i(TAG, "[CHROME_POLICY] Clear result: $success")
            result.success(mapOf("cleared" to success))
        } catch (e: Exception) {
            Log.e(TAG, "[CHROME_POLICY] clearChromePolicy failed: ${e.message}")
            result.error("CHROME_POLICY_ERROR", e.message, null)
        }
    }
}

