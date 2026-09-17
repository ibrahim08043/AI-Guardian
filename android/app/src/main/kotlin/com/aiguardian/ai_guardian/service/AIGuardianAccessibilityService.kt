package com.aiguardian.ai_guardian.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.aiguardian.ai_guardian.MainActivity
import com.aiguardian.ai_guardian.enforcement.BlockActivity
import com.aiguardian.ai_guardian.enforcement.EnforcementManager
import com.aiguardian.ai_guardian.policy.ForegroundMonitor
import com.aiguardian.ai_guardian.policy.PolicyEngine
import com.aiguardian.ai_guardian.policy.PolicyResult
import com.aiguardian.ai_guardian.policy.UsageTracker
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper
import com.aiguardian.ai_guardian.storage.PolicyRepository

/**
 * AI Guardian's AccessibilityService — foreground app detection + policy evaluation + enforcement.
 *
 * This service detects when the user switches between Android applications
 * by handling [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] events.
 *
 * The enforcement path:
 * - ALLOW: app continues normally, no intervention
 * - BLOCK: BlockActivity is launched with cooldown protection
 * - AI Guardian package is never blocked (self-protection)
 *
 * Privacy: This service only reads the event's package name. It does NOT
 * read screen text, passwords, messages, input fields, or any sensitive data.
 */
class AIGuardianAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AIGuardianA11y"

        /** Static reference for checking service state from other components. */
        @Volatile
        var instance: AIGuardianAccessibilityService? = null
            private set

        /** Injected PolicyEngine with persistent storage. */
        @Volatile
        var policyEngine: PolicyEngine? = null

        /** Injected UsageTracker for session tracking. */
        @Volatile
        var usageTracker: UsageTracker? = null

        /** Returns true if the service is currently connected and running. */
        val isRunning: Boolean
            get() = instance != null
    }

    @Volatile
    private var lastForegroundPackage: String? = null

    /** Deterministic enforcement manager — handles blocking interventions. */
    internal val enforcementManager = EnforcementManager()

    /** Real-time boundary monitor for schedule/limit enforcement. */
    private var foregroundMonitor: ForegroundMonitor? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AI Guardian AccessibilityService connected")

        val engine = policyEngine ?: PolicyEngine()
        val repo = try {
            PolicyRepository(PolicyDatabaseHelper(applicationContext))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PolicyRepository for monitor: ${e.message}")
            null
        }
        foregroundMonitor = ForegroundMonitor(engine, repo, object : ForegroundMonitor.EnforcementCallback {
            override fun onEnforce(packageName: String, result: PolicyResult) {
                Log.i(TAG, "ForegroundMonitor enforcement: $packageName (${result.reason})")
                if (enforcementManager.shouldEnforce(packageName, result.action)) {
                    try {
                        BlockActivity.launch(applicationContext, packageName, result.reason)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch BlockActivity from monitor: ${e.message}", e)
                    }
                }
            }
        })
        Log.i(TAG, "ForegroundMonitor initialized (repository=${if (repo != null) "OK" else "NULL"})")

        policyEngine?.let { foregroundMonitor?.updateEngine(it) }

        serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return
        if (packageName == lastForegroundPackage) return

        val previousPackage = lastForegroundPackage
        lastForegroundPackage = packageName
        Log.i(TAG, "FG app: $previousPackage → $packageName")

        // Usage session tracking
        val tracker = usageTracker ?: policyEngine?.usageTracker
        if (tracker != null) {
            try {
                if (previousPackage != null) {
                    tracker.endSession()
                }
                tracker.startSession(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Usage tracking error: ${e.message}")
            }
        }

        // Policy evaluation
        val currentEngine = policyEngine
        if (currentEngine != null) {
            foregroundMonitor?.updateEngine(currentEngine)
        }
        val monitor = foregroundMonitor
        val policyResult = if (monitor != null) {
            monitor.onForegroundAppChanged(packageName)
        } else {
            val engine = policyEngine ?: PolicyEngine()
            engine.evaluate(packageName)
        }
        Log.i(TAG, "Policy result: action=${policyResult.action} matched=${policyResult.matched} reason=${policyResult.reason}")

        // Enforcement
        val shouldEnforce = enforcementManager.shouldEnforce(packageName, policyResult.action)
        if (shouldEnforce) {
            Log.i(TAG, "Blocking enforcement triggered for $packageName (reason: ${policyResult.reason})")
            try {
                BlockActivity.launch(applicationContext, packageName, policyResult.reason)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockActivity: ${e.message}", e)
            }
        }

        // Emit event to Flutter
        val sink = MainActivity.eventSink
        if (sink != null) {
            try {
                val eventMap = mapOf(
                    "event" to "foregroundAppChanged",
                    "packageName" to packageName,
                    "policyAction" to policyResult.action.name,
                    "policyMatched" to policyResult.matched,
                    "reason" to policyResult.reason,
                )
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        MainActivity.eventSink?.success(eventMap)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to emit foreground event: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to post foreground event: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "AI Guardian AccessibilityService interrupted")
    }

    override fun onDestroy() {
        foregroundMonitor?.cancelAll()
        foregroundMonitor = null

        try {
            usageTracker?.endSession()
            policyEngine?.usageTracker?.endSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error ending session on destroy: ${e.message}")
        }

        instance = null
        lastForegroundPackage = null
        Log.i(TAG, "AI Guardian AccessibilityService destroyed")
        super.onDestroy()
    }

    fun notifyPolicyChanged(packageName: String) {
        foregroundMonitor?.onPolicyChanged(packageName)
    }
}
