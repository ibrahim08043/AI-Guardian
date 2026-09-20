package com.aiguardian.ai_guardian.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aiguardian.ai_guardian.MainActivity
import com.aiguardian.ai_guardian.contentfilter.ContentFilterEngine
import com.aiguardian.ai_guardian.contentfilter.ContentFilterRepository
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
        private const val OWN_PACKAGE = "com.aiguardian.ai_guardian"

        /** Maximum depth for recursive node tree text extraction. */
        private const val MAX_NODE_DEPTH = 15

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

        /** Injected ContentFilterEngine for text content filtering. */
        @Volatile
        var contentFilterEngine: ContentFilterEngine? = null

        /** Injected ContentFilterRepository for loading rules. */
        @Volatile
        var contentFilterRepository: ContentFilterRepository? = null

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

    /**
     * Debounce tracking for content filter blocks.
     * Maps (packageName, matchedPhrase) → last block time.
     * Prevents repeated blocking of the same content in the same app.
     */
    private val contentFilterBlockTimes = mutableMapOf<String, Long>()

    /** Cooldown for content filter blocks per (package, phrase) pair. */
    private val contentFilterCooldownMs = 3000L

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

        // Load content filter rules into the engine
        val filterRepo = contentFilterRepository ?: try {
            ContentFilterRepository(PolicyDatabaseHelper(applicationContext))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ContentFilterRepository: ${e.message}")
            null
        }
        val filterEngine = contentFilterEngine ?: ContentFilterEngine()
        if (contentFilterRepository == null) {
            contentFilterRepository = filterRepo
        }
        if (contentFilterEngine == null) {
            contentFilterEngine = filterEngine
        }
        try {
            val rules = filterRepo?.getEnabledRules() ?: emptyList()
            filterEngine.updateRules(rules)
            Log.i(TAG, "ContentFilterEngine initialized with ${rules.size} rules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load content filter rules: ${e.message}")
        }

        policyEngine?.let { foregroundMonitor?.updateEngine(it) }

        serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return

        // ── TYPE_WINDOW_STATE_CHANGED: foreground app tracking + policy ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(event, packageName)
            return
        }

        // ── TYPE_VIEW_TEXT_CHANGED / TYPE_WINDOW_CONTENT_CHANGED: content filter ──
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleTextEvent(event, eventType, packageName)
            return
        }
    }

    /**
     * Handle TYPE_WINDOW_STATE_CHANGED — existing foreground app detection
     * and policy evaluation. Unchanged from original behavior.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent, packageName: String) {
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
        emitForegroundEvent(packageName, policyResult)
    }

    /**
     * Handle TYPE_VIEW_TEXT_CHANGED and TYPE_WINDOW_CONTENT_CHANGED.
     * Extracts text from the event and evaluates it against content filter rules.
     *
     * Text extraction priority:
     * 1. event.text (direct text from the event)
     * 2. event.contentDescription (content description attribute)
     * 3. Node tree traversal from event.source (when canRetrieveWindowContent=true)
     */
    private fun handleTextEvent(event: AccessibilityEvent, eventType: Int, packageName: String) {
        // Skip AI Guardian's own events
        if (packageName == OWN_PACKAGE) return

        val filterEngine = contentFilterEngine
        if (filterEngine == null || !filterEngine.isMasterEnabled()) return

        val enabledRuleCount = filterEngine.getEnabledRuleCount()
        if (enabledRuleCount == 0) return

        val eventTypeStr = if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) "TEXT_CHANGED" else "CONTENT_CHANGED"
        Log.d(TAG, "[Filter] $eventTypeStr from $packageName (rules=$enabledRuleCount, master=ON)")

        // Extract text from multiple sources
        val extractedText = extractTextFromEvent(event)

        if (extractedText.isEmpty()) {
            Log.d(TAG, "[Filter] No text extracted from $eventTypeStr event")
            return
        }

        Log.d(TAG, "[Filter] Text length=${extractedText.length}")

        // Evaluate against content filter engine
        val filterResult = filterEngine.evaluateText(extractedText, packageName)

        if (!filterResult.matched) {
            Log.d(TAG, "[Filter] No match (rule ${filterResult.ruleId})")
            return
        }

        Log.i(TAG, "[Filter] Match: ruleId=${filterResult.ruleId} mode=${filterResult.matchMode} pkg=$packageName")

        // Debounce: first match triggers immediately, repeated matches within cooldown are suppressed
        val debounceKey = "$packageName:${filterResult.matchedPhrase}"
        val now = System.currentTimeMillis()
        val lastBlock = contentFilterBlockTimes[debounceKey] ?: 0L

        if (now - lastBlock <= contentFilterCooldownMs) {
            Log.d(TAG, "[Filter] Debounce active (${now - lastBlock}ms < ${contentFilterCooldownMs}ms)")
            return
        }

        contentFilterBlockTimes[debounceKey] = now
        val reason = "Content filter match (rule ${filterResult.ruleId}, ${filterResult.matchMode})"
        Log.i(TAG, "[Filter] ENFORCING: BlockActivity for $packageName (reason: $reason)")

        try {
            BlockActivity.launch(applicationContext, packageName, reason)
            Log.i(TAG, "[Filter] BlockActivity launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[Filter] BlockActivity launch FAILED: ${e.message}", e)
        }
    }

    /**
     * Extract text from an accessibility event using multiple sources.
     * Returns the combined text, or empty string if no text found.
     *
     * Sources checked in order:
     * 1. event.text — direct text from the event (available without canRetrieveWindowContent)
     * 2. event.contentDescription — content description attribute
     * 3. Node tree from event.source — full text from the active view hierarchy
     */
    private fun extractTextFromEvent(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()

        // Source 1: event.text
        val eventText = event.text
        if (eventText != null && eventText.isNotEmpty()) {
            for (cs in eventText) {
                if (cs != null && cs.isNotEmpty()) {
                    parts.add(cs.toString())
                }
            }
        }

        // Source 2: event.contentDescription
        val contentDesc = event.contentDescription
        if (contentDesc != null && contentDesc.isNotEmpty()) {
            parts.add(contentDesc.toString())
        }

        // Source 3: Node tree from event.source (only if canRetrieveWindowContent=true)
        if (parts.isEmpty()) {
            try {
                val sourceNode = event.source
                if (sourceNode != null) {
                    val nodeText = extractTextFromNode(sourceNode, 0)
                    if (nodeText.isNotEmpty()) {
                        parts.add(nodeText)
                    }
                    sourceNode.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Filter] Node extraction failed: ${e.message}")
            }
        }

        return parts.joinToString(" ").trim()
    }

    /**
     * Recursively extract text from an AccessibilityNodeInfo tree.
     *
     * Constraints:
     * - Maximum depth: [MAX_NODE_DEPTH] levels
     * - Maximum text length: [ContentFilterEngine.MAX_TEXT_LENGTH] characters
     * - Skips null/stale nodes
     * - Does NOT recycle nodes (caller manages source node recycling)
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > MAX_NODE_DEPTH) return ""

        val parts = mutableListOf<String>()

        // Get text from this node
        val nodeText = node.text
        if (nodeText != null && nodeText.isNotEmpty()) {
            parts.add(nodeText.toString())
        }

        // Get content description
        val nodeDesc = node.contentDescription
        if (nodeDesc != null && nodeDesc.isNotEmpty()) {
            parts.add(nodeDesc.toString())
        }

        // Recurse into children
        val childCount = node.childCount
        if (childCount > 0 && depth < MAX_NODE_DEPTH) {
            for (i in 0 until childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        val childText = extractTextFromNode(child, depth + 1)
                        if (childText.isNotEmpty()) {
                            parts.add(childText)
                        }
                    }
                } catch (e: Exception) {
                    // Skip inaccessible children
                }

                // Stop if we've accumulated enough text
                if (parts.joinToString(" ").length > ContentFilterEngine.MAX_TEXT_LENGTH) break
            }
        }

        return parts.joinToString(" ").take(ContentFilterEngine.MAX_TEXT_LENGTH)
    }

    /**
     * Emit foreground app event to Flutter EventChannel.
     */
    private fun emitForegroundEvent(packageName: String, policyResult: PolicyResult) {
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

    /**
     * Refresh content filter rules from the database.
     * Called when rules are added, updated, deleted, or toggled from Flutter.
     */
    fun refreshContentFilterRules() {
        val engine = contentFilterEngine ?: return
        val repo = contentFilterRepository ?: return
        try {
            val rules = repo.getEnabledRules()
            engine.updateRules(rules)
            Log.i(TAG, "Content filter rules refreshed: ${rules.size} enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh content filter rules: ${e.message}")
        }
    }
}
