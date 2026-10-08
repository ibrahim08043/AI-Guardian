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
import com.aiguardian.ai_guardian.enforcement.UninstallGuardActivity
import com.aiguardian.ai_guardian.enforcement.SettingsAccessibilityGuardActivity
import com.aiguardian.ai_guardian.enforcement.EnforcementManager
import com.aiguardian.ai_guardian.policy.DomainPolicy
import com.aiguardian.ai_guardian.policy.ForegroundMonitor
import com.aiguardian.ai_guardian.policy.PersistentAppBlockSeeder
import com.aiguardian.ai_guardian.policy.PolicyEngine
import com.aiguardian.ai_guardian.policy.PolicyResult
import com.aiguardian.ai_guardian.policy.UsageTracker
import com.aiguardian.ai_guardian.storage.DomainRepository
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

        /** Log tag prefix for uninstall diagnostic output. */
        private const val DIAG_TAG = "AI_GUARDIAN_UNINSTALL_DIAG"

        /** Log tag for performance instrumentation. */
        private const val PERF_TAG = "AI_GUARDIAN_PERF"

        /** Threshold (ms) above which event processing is logged as slow. */
        private const val PERF_SLOW_THRESHOLD_MS = 50L

        /** Maximum depth for recursive node tree text extraction. */
        private const val MAX_NODE_DEPTH = 15

        /** Maximum number of diagnostic events to log per session (prevents logcat flooding). */
        private const val DIAG_MAX_EVENTS = 30

        /** Maximum character length for flattened window text in diagnostic output. */
        private const val DIAG_FLATTEN_MAX_CHARS = 800

        /** Log tag prefix for uninstall guard diagnostic output. */
        private const val GUARD_TAG = "AI_GUARDIAN_UNINSTALL_GUARD"

        /** Cooldown after guard action to prevent repeated triggering (ms). */
        private const val UNINSTALL_GUARD_COOLDOWN_MS = 5000L

        /** Package name for the Android Package Installer (uninstall confirmation). */
        private const val PACKAGE_INSTALLER = "com.google.android.packageinstaller"

        // ── Web diagnostic constants ──────────────────────────────────────────
        /** Log tag for browser website detection diagnostic output. */
        private const val WEB_DIAG_TAG = "AI_GUARDIAN_WEB_DIAG"

        /** Primary browser package name to monitor. */
        private const val PACKAGE_CHROME = "com.android.chrome"

        /**
         * Additional browser packages to monitor (if naturally received).
         * Chrome is primary; others are logged but not hardcoded for blocking.
         */
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",       // Edge
            "com.UCMobile",             // UC Browser
            "com.brave.browser",        // Brave
        )

        /** Maximum events per session for web diagnostics (prevents logcat flooding). */
        private const val WEB_DIAG_MAX_EVENTS = 30

        /** Cooldown between identical URL candidate logs (ms). */
        private const val WEB_DIAG_URL_COOLDOWN_MS = 10000L

        /** Maximum character length for web diagnostic window summary. */
        private const val WEB_DIAG_FLATTEN_MAX_CHARS = 1200

        /** Maximum node depth for web diagnostic traversal. */
        private const val WEB_DIAG_MAX_NODE_DEPTH = 5

        // ── Settings accessibility guard constants ────────────────────────────
        /** Log tag for Settings accessibility diagnostic output. */
        private const val SETTINGS_DIAG_TAG = "AI_GUARDIAN_ACCESSIBILITY_SETTINGS_DIAG"

        /** Log tag for Settings accessibility guard actions. */
        private const val SETTINGS_GUARD_TAG = "AI_GUARDIAN_SETTINGS_GUARD"

        /** Maximum diagnostic events for Settings accessibility logging. */
        private const val SETTINGS_DIAG_MAX_EVENTS = 30

        /** Cooldown after guard action to prevent repeated triggering (ms). */
        private const val SETTINGS_GUARD_COOLDOWN_MS = 5000L

        /** Maximum character length for Settings diagnostic window summary. */
        private const val SETTINGS_DIAG_FLATTEN_MAX_CHARS = 1200

        /**
         * Fully-qualified class name of the password gate Activity. Used to
         * distinguish the gate's own window (an overlay ON the protected
         * Settings page) from other AI Guardian windows when tracking whether
         * the protected page has left the foreground.
         */
        private val SETTINGS_GUARD_ACTIVITY_CLASS: String =
            SettingsAccessibilityGuardActivity::class.java.name

        // ── Web block constants ──────────────────────────────────────────────
        /** Log tag for website blocking actions. */
        private const val WEB_BLOCK_TAG = "AIGuardianWebBlock"

        /** Per-domain debounce interval for website blocking (ms). */
        private const val WEB_BLOCK_DEBOUNCE_MS = 5000L

        /** Maximum size for debounce/timestamp maps before cleanup. */
        private const val MAX_DEBOUNCE_MAP_SIZE = 200

        /** TTL for stale debounce entries (ms). */
        private const val DEBOUNCE_STALE_TTL_MS = 60000L

        // ── Pre-compiled regex patterns (compiled once, reused per event) ──
        private val REGEX_FULL_URL = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)
        private val REGEX_WWW = Regex("""www\.[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}[^\s<>"')\]]*""", RegexOption.IGNORE_CASE)
        private val REGEX_DOMAIN = Regex("""[a-zA-Z0-9][-a-zA-Z0-9]*(?:\.[a-zA-Z0-9][-a-zA-Z0-9]*)*\.[a-zA-Z]{2,13}(?:/[^\s<>"')\]]*)?""", RegexOption.IGNORE_CASE)

        /** Reusable Handler for posting events to main thread. */
        private val MAIN_HANDLER = android.os.Handler(android.os.Looper.getMainLooper())

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

    /** Diagnostic event counter — stops logging after [DIAG_MAX_EVENTS] to avoid logcat flooding. */
    @Volatile
    private var diagEventCount = 0

    // ── Uninstall guard state ────────────────────────────────────────────────
    /** Whether the uninstall guard is currently active (password gate showing). */
    @Volatile
    private var uninstallGuardActive = false

    /** Timestamp of last guard trigger — enforces cooldown to prevent loops. */
    @Volatile
    private var lastGuardTriggerTime = 0L

    // ── Health tracking ─────────────────────────────────────────────────────
    /** Monotonic counter incremented each time caches are successfully reloaded. */
    @Volatile
    private var cacheGeneration = 0L

    /** Last time content filter rules were successfully loaded (epoch ms). */
    @Volatile
    private var lastContentFilterLoadTime = 0L

    /** Last time blocked domains were successfully loaded (epoch ms). */
    @Volatile
    private var lastBlockedDomainsLoadTime = 0L

    // ── Web diagnostic state ─────────────────────────────────────────────────
    /** Diagnostic event counter for web diagnostics — stops after [WEB_DIAG_MAX_EVENTS]. */
    @Volatile
    private var webDiagEventCount = 0

    /** Tracks last logged URL candidates for deduplication cooldown. */
    private val webDiagUrlTimestamps = mutableMapOf<String, Long>()

    /** Last package seen in browser window — used to detect page changes. */
    @Volatile
    private var lastBrowserPackage: String? = null

    // ── Settings accessibility guard state ──────────────────────────────────
    /** Diagnostic event counter for Settings accessibility diagnostics. */
    @Volatile
    private var settingsDiagEventCount = 0

    /** Whether the settings accessibility guard is currently active (password gate showing). */
    @Volatile
    private var settingsAccessibilityGuardActive = false

    /** Timestamp of last settings guard trigger — enforces cooldown to prevent loops. */
    @Volatile
    private var lastSettingsGuardTriggerTime = 0L

    /**
     * Whether the user is currently ON the protected AI Guardian Accessibility
     * Settings page (the page with the AccessibilityService enable/disable toggle).
     *
     * Distinct from [settingsAccessibilityGuardActive] (password gate showing).
     * This tracks the *page context* so the guard can be re-armed when the
     * protected page becomes foreground/active again — including when Android
     * resumes an EXISTING Settings activity/task via Home → Recent Apps rather
     * than creating a new Activity. Re-arm is driven by this flag's
     * absent→present EDGE (a departure, then an arrival), never by elapsed
     * time, so the protection is restored no matter how fast the resume is.
     */
    @Volatile
    private var settingsProtectedPageActive = false

    /**
     * Whether the password gate has already been satisfied during the CURRENT
     * visit to the protected page.
     *
     * Set when the gate is dismissed ([clearSettingsAccessibilityGuard]) and
     * cleared whenever the protected page is left or freshly (re-)entered.
     * This prevents re-prompting loops on the many repeated
     * WINDOW_STATE_CHANGED events Settings emits for the same page, WITHOUT
     * weakening the guard: a fresh arrival on the page always re-arms.
     */
    @Volatile
    private var settingsAuthorizedVisit = false

    // ── Website blocking state ───────────────────────────────────────────────
    /** Normalized blocked domains loaded from DomainRepository. */
    private val blockedDomains = mutableSetOf<String>()

    /** Per-domain debounce timestamps for website blocking. */
    private val webBlockDomainTimestamps = mutableMapOf<String, Long>()

    /** Last blocked web domain (for dedup logging). */
    @Volatile
    private var lastBlockedWebDomain: String? = null

    /** Event counter for periodic map cleanup. */
    @Volatile
    private var eventCount = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AI Guardian AccessibilityService connected")

        val repo = try {
            PolicyRepository(PolicyDatabaseHelper(applicationContext))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PolicyRepository for monitor: ${e.message}")
            null
        }

        // ── REBOOT LIFECYCLE RESTORATION ──────────────────────────────────
        // The repository-backed PolicyEngine and UsageTracker are normally
        // injected by MainActivity.configureFlutterEngine() when the Flutter
        // UI starts. After a device reboot, Android re-creates this service
        // in a FRESH process before (or without) MainActivity ever running,
        // so those statics are still null. Without this restoration the
        // fallback engine would have no PolicyRepository, evaluate() could
        // only see an empty in-memory map, and every SQLite-backed app
        // blocking rule (including persistent package rules) would silently
        // return ALLOW until the UI was manually opened.
        //
        // When the statics have already been injected (normal, pre-reboot
        // behavior), this block is a no-op and the existing engine is used
        // exactly as before. If MainActivity later injects its own engine,
        // ForegroundMonitor.updateEngine() swaps it in unchanged.
        val engine = policyEngine ?: PolicyEngine(repo).also { restored ->
            val tracker = usageTracker ?: repo?.let { UsageTracker(it) }
            restored.usageTracker = tracker
            policyEngine = restored
            if (tracker != null) {
                usageTracker = tracker
            }
            Log.i(
                TAG,
                "Reboot restore: repository-backed PolicyEngine initialized " +
                    "(repository=${if (repo != null) "OK" else "NULL"}, " +
                    "usageTracker=${if (tracker != null) "OK" else "NULL"})"
            )
        }

        // Ensure persistent Facebook/Reddit BLOCK rules exist on every service start.
        // Idempotent — does not create duplicates and does not touch other policies.
        try {
            PersistentAppBlockSeeder.ensurePersistentBlocks(engine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed persistent app block rules on service connect: ${e.message}", e)
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
            // Fail-safe: only replace rules if we got a non-empty result.
            // A DB error during startup must NOT destroy previously loaded rules.
            if (rules.isNotEmpty()) {
                filterEngine.updateRules(rules)
                lastContentFilterLoadTime = System.currentTimeMillis()
                cacheGeneration++
                Log.i(TAG, "AI_GUARDIAN_SERVICE_HEALTH: ContentFilterEngine initialized with ${rules.size} rules (gen=$cacheGeneration)")
            } else {
                val existingCount = filterEngine.getEnabledRuleCount()
                if (existingCount > 0) {
                    Log.w(TAG, "AI_GUARDIAN_SERVICE_HEALTH: Content filter DB returned 0 rules — keeping existing $existingCount rules")
                } else {
                    Log.w(TAG, "AI_GUARDIAN_SERVICE_HEALTH: Content filter DB returned 0 rules, no existing cache")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: stage=loadContentFilterRules exception=${e.javaClass.simpleName} message=${e.message}")
        }

        policyEngine?.let { foregroundMonitor?.updateEngine(it) }

        // Refresh in-memory policy cache (eliminates per-event DB queries)
        try {
            policyEngine?.refreshCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh policy cache: ${e.message}")
        }

        // Load blocked domains for Phase 3B website blocking
        try {
            val domainRepo = DomainRepository(PolicyDatabaseHelper(applicationContext))
            val domainSet = domainRepo.getBlockedDomainSet()
            // Fail-safe: only replace if we got a non-empty result.
            // A DB error during startup must NOT destroy previously loaded domains.
            if (domainSet.isNotEmpty()) {
                blockedDomains.clear()
                blockedDomains.addAll(domainSet)
                lastBlockedDomainsLoadTime = System.currentTimeMillis()
                cacheGeneration++
                Log.i(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_HEALTH: Loaded ${blockedDomains.size} blocked domains (gen=$cacheGeneration)")
            } else {
                val existingCount = blockedDomains.size
                if (existingCount > 0) {
                    Log.w(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_HEALTH: Domain DB returned 0 domains — keeping existing $existingCount domains")
                } else {
                    Log.w(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_HEALTH: Domain DB returned 0 domains, no existing cache")
                }
            }
        } catch (e: Exception) {
            Log.e(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_ERROR: stage=loadBlockedDomains exception=${e.javaClass.simpleName} message=${e.message}")
        }

        // ── Health snapshot after all caches loaded ──
        val contentRuleCount = contentFilterEngine?.getEnabledRuleCount() ?: 0
        val policyCount = try { policyEngine?.getPolicies()?.size ?: 0 } catch (_: Exception) { -1 }
        Log.i(TAG, "AI_GUARDIAN_SERVICE_HEALTH: serviceConnected=true " +
            "contentRules=$contentRuleCount " +
            "blockedDomains=${blockedDomains.size} " +
            "policies=$policyCount " +
            "cacheGen=$cacheGeneration")

        serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventStartTime = System.currentTimeMillis()

        // Periodic cleanup of unbounded maps (every 500 events)
        eventCount++
        if (eventCount % 500 == 0L) {
            try {
                cleanupStaleEntries()
            } catch (e: Exception) {
                Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: cleanup failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // ── UNINSTALL GUARD: check for uninstall attempts before any routing ──
        try {
            checkUninstallGuard(event)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: uninstall guard: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── SETTINGS ACCESSIBILITY GUARD: check for AI Guardian disable attempts ──
        try {
            checkSettingsAccessibilityGuard(event)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: settings a11y guard: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── SETTINGS A11Y DIAGNOSTIC: log Settings events for screen discovery ──
        try {
            logSettingsAccessibilityDiagnostic(event)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: settings a11y diag: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── WEB DIAGNOSTIC: log browser events to discover URL exposure ──
        try {
            logBrowserDiagnostic(event)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: browser diagnostic: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── DIAGNOSTIC LAYER: log every relevant event before any routing ──
        try {
            logUninstallDiagnostic(event)
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: uninstall diagnostic: ${e.javaClass.simpleName}: ${e.message}")
        }

        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty()) return

        // ── TYPE_WINDOW_STATE_CHANGED: foreground app tracking + policy ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                handleWindowStateChanged(event, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: windowStateChanged: ${e.javaClass.simpleName}: pkg=$packageName ${e.message}")
            }
            // Performance logging for slow events
            val elapsed = System.currentTimeMillis() - eventStartTime
            if (elapsed > PERF_SLOW_THRESHOLD_MS) {
                Log.w(PERF_TAG, "SLOW_EVENT: type=WINDOW_STATE_CHANGED pkg=$packageName elapsed=${elapsed}ms")
            }
            return
        }

        // ── TYPE_VIEW_TEXT_CHANGED / TYPE_WINDOW_CONTENT_CHANGED: content filter ──
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            try {
                handleTextEvent(event, eventType, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: textEvent: ${e.javaClass.simpleName}: pkg=$packageName ${e.message}")
            }
            // Performance logging for slow events
            val elapsed = System.currentTimeMillis() - eventStartTime
            if (elapsed > PERF_SLOW_THRESHOLD_MS) {
                Log.w(PERF_TAG, "SLOW_EVENT: type=$eventType pkg=$packageName elapsed=${elapsed}ms")
            }
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

        // Policy evaluation (uses in-memory cache — no DB query)
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

        // Enforcement
        val shouldEnforce = enforcementManager.shouldEnforce(packageName, policyResult.action)
        if (shouldEnforce) {
            Log.i(TAG, "Blocking: $packageName (${policyResult.reason})")
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

        // Extract text from multiple sources
        val extractedText = extractTextFromEvent(event)

        if (extractedText.isEmpty()) return

        // Evaluate against content filter engine
        val filterResult = filterEngine.evaluateText(extractedText, packageName)

        if (!filterResult.matched) return

        Log.i(TAG, "[Filter] Match: ruleId=${filterResult.ruleId} mode=${filterResult.matchMode} pkg=$packageName")

        // TEMPORARILY DISABLED — CONTENT FILTER MATCH BLOCKING
        // ContentFilterEngine matching still runs above; only the
        // match → BlockActivity enforcement conversion is commented out.
        // Restore the original block below to re-enable content-filter blocking.
        //
        // // Debounce: first match triggers immediately, repeated matches within cooldown are suppressed
        // val debounceKey = "$packageName:${filterResult.matchedPhrase}"
        // val now = System.currentTimeMillis()
        // val lastBlock = contentFilterBlockTimes[debounceKey] ?: 0L
        //
        // if (now - lastBlock <= contentFilterCooldownMs) {
        //     Log.d(TAG, "[Filter] Debounce active (${now - lastBlock}ms < ${contentFilterCooldownMs}ms)")
        //     return
        // }
        //
        // contentFilterBlockTimes[debounceKey] = now
        // val reason = "Content filter match (rule ${filterResult.ruleId}, ${filterResult.matchMode})"
        // Log.i(TAG, "[Filter] ENFORCING: BlockActivity for $packageName (reason: $reason)")
        //
        // try {
        //     BlockActivity.launch(applicationContext, packageName, reason)
        //     Log.i(TAG, "[Filter] BlockActivity launched successfully")
        // } catch (e: Exception) {
        //     Log.e(TAG, "[Filter] BlockActivity launch FAILED: ${e.message}", e)
        // }

        Log.i(
            TAG,
            "[Filter] Match detected but Content Filter match→block TEMPORARILY DISABLED — " +
                "not launching BlockActivity for $packageName"
        )
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

        val sb = StringBuilder(ContentFilterEngine.MAX_TEXT_LENGTH)

        // Get text from this node
        val nodeText = node.text
        if (nodeText != null && nodeText.isNotEmpty()) {
            sb.append(nodeText)
        }

        // Get content description
        val nodeDesc = node.contentDescription
        if (nodeDesc != null && nodeDesc.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(nodeDesc)
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
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(childText)
                        }
                    }
                } catch (e: Exception) {
                    // Skip inaccessible children
                }

                // Stop if we've accumulated enough text
                if (sb.length > ContentFilterEngine.MAX_TEXT_LENGTH) break
            }
        }

        return sb.toString().take(ContentFilterEngine.MAX_TEXT_LENGTH)
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
                MAIN_HANDLER.post {
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

    // ──────────────────────────────────────────────────────────────────────────
    //  UNINSTALL GUARD (Phase 2 — detection + password gate)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check whether the current accessibility event represents an uninstall
     * attempt on AI Guardian. If so, interrupt the flow and show the password
     * protection gate.
     *
     * This is a BEST-EFFORT AccessibilityService protection layer, NOT Device
     * Owner protection. The protection depends on the AccessibilityService
     * remaining enabled. System UI changes, service disablement, Safe Mode,
     * ADB, or other system-level paths may bypass it.
     *
     * Detection targets two stages:
     * 1. Package Installer uninstall confirmation dialog
     * 2. Settings App Info page (with "Uninstall" button visible)
     *
     * Only the Package Installer confirmation triggers the password gate.
     * The Settings App Info page is logged but does NOT trigger the guard
     * (the user is merely viewing app info, not confirming uninstall).
     */
    private fun checkUninstallGuard(event: AccessibilityEvent) {
        // Skip if guard is already active (prevent loops)
        if (uninstallGuardActive) return

        // Skip AI Guardian's own events
        val packageName = event.packageName?.toString() ?: return
        if (packageName == OWN_PACKAGE) return

        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastGuardTriggerTime < UNINSTALL_GUARD_COOLDOWN_MS) {
            Log.d(GUARD_TAG, "COOLDOWN (${now - lastGuardTriggerTime}ms < ${UNINSTALL_GUARD_COOLDOWN_MS}ms)")
            return
        }

        // Detect Package Installer uninstall confirmation
        if (detectPackageInstallerUninstall(event)) {
            Log.w(GUARD_TAG, "AI_GUARDIAN_UNINSTALL_GUARD: DETECTED")
            triggerUninstallGuard()
        }
    }

    /**
     * Detect whether the current event represents the Package Installer's
     * uninstall confirmation dialog for AI Guardian.
     *
     * Primary condition:
     *   Package = com.google.android.packageinstaller
     *   AND at least two of:
     *   - visible text contains "AI Guardian"
     *   - visible text contains "Do you want to uninstall this app?"
     *   - active window class is android.app.AlertDialog
     *   - window summary contains "Cancel" and "OK"
     */
    private fun detectPackageInstallerUninstall(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return false

        // Must be the Package Installer
        if (packageName != PACKAGE_INSTALLER) return false

        // Collect visible text signals
        val allVisibleText = getFlattenedWindowTextSummary().lowercase()
        val eventText = event.text?.joinToString(" ") { it?.toString() ?: "" }?.lowercase() ?: ""
        val combinedText = "$allVisibleText $eventText"

        // Signal 1: visible text contains "AI Guardian"
        val hasAiGuardianName = combinedText.contains("ai guardian")

        // Signal 2: visible text contains the uninstall confirmation prompt
        val hasUninstallPrompt = combinedText.contains("do you want to uninstall this app")

        // Signal 3: active window class is AlertDialog
        var isAlertDialog = false
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val rootClass = root.className?.toString() ?: ""
                isAlertDialog = rootClass.contains("AlertDialog", ignoreCase = true)
                root.recycle()
            }
        } catch (_: Exception) { /* unavailable */ }

        // Signal 4: window summary contains "Cancel" and "OK"
        val hasCancelAndOk = combinedText.contains("cancel") && combinedText.contains("ok")

        // Require at least 2 signals for positive detection
        val signalCount = listOf(hasAiGuardianName, hasUninstallPrompt, isAlertDialog, hasCancelAndOk).count { it }

        if (signalCount >= 2) {
            Log.i(GUARD_TAG, "Package Installer uninstall confirmed (signals=$signalCount): " +
                "aiGuardian=$hasAiGuardianName prompt=$hasUninstallPrompt " +
                "alertDialog=$isAlertDialog cancelOk=$hasCancelAndOk")
        }

        return signalCount >= 2
    }

    /**
     * Trigger the uninstall guard: interrupt the uninstall flow and show
     * the password protection gate.
     *
     * Steps:
     * 1. Set guard active (prevent re-triggering)
     * 2. Log the detection
     * 3. Attempt to interrupt the uninstall via GLOBAL_ACTION_BACK
     * 4. Launch the UninstallGuardActivity password gate
     */
    private fun triggerUninstallGuard() {
        val now = System.currentTimeMillis()

        // Set guard active
        uninstallGuardActive = true
        lastGuardTriggerTime = now

        Log.i(GUARD_TAG, "AI_GUARDIAN_UNINSTALL_GUARD: INTERRUPT_ATTEMPT")

        // Attempt to interrupt the uninstall confirmation via back action
        val interruptResult = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(GUARD_TAG, "GLOBAL_ACTION_BACK failed: ${e.message}")
            false
        }
        Log.i(GUARD_TAG, "AI_GUARDIAN_UNINSTALL_GUARD: INTERRUPT_RESULT=$interruptResult")

        // Launch the password protection gate
        try {
            UninstallGuardActivity.launch(applicationContext)
            Log.i(GUARD_TAG, "AI_GUARDIAN_UNINSTALL_GUARD: PASSWORD_GATE_SHOWN")
        } catch (e: Exception) {
            Log.e(GUARD_TAG, "Failed to launch UninstallGuardActivity: ${e.message}")
            // If we can't show the gate, at least the back action interrupted the flow
        }
    }

    /**
     * Called by UninstallGuardActivity when the password gate is dismissed
     * (correct password, wrong password retries, or cancel/back).
     * Clears the guard active state so future uninstall attempts can be detected.
     */
    fun clearUninstallGuard() {
        uninstallGuardActive = false
        Log.i(GUARD_TAG, "AI_GUARDIAN_UNINSTALL_GUARD: GUARD_CLEARED")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SETTINGS ACCESSIBILITY GUARD (detection + password gate)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check whether the current accessibility event represents the user
     * navigating to AI Guardian's AccessibilityService details page in
     * Android Settings and attempting to disable it.
     *
     * Detection targets the specific Settings screen where AI Guardian's
     * AccessibilityService can be managed (enabled/disabled). This is a
     * SURGICAL check — only this specific page is protected.
     *
     * Uses multi-signal detection (same pattern as uninstall guard):
     * - Settings package
     * - AI Guardian name in visible text
     * - Accessibility-related class/activity
     * - Toggle/switch node present
     *
     * Requires ≥2 signals for positive detection.
     */
    private fun checkSettingsAccessibilityGuard(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // NOTE: do NOT early-return merely because the gate is currently
        // "active". Pressing Home backgrounds (does not destroy) the gate, so
        // the flag stays set; an early return here is exactly the stale-latch
        // that caused the Home → Recents bypass. The page-context state machine
        // below runs regardless and re-arms on the departure/arrival edge.
        val now = System.currentTimeMillis()
        val isWindowStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // ── LEAVE DETECTION ──────────────────────────────────────────────────
        // The user has left the protected AI Guardian Accessibility page.
        //
        // A departure is a window-state change bringing a foreground context
        // that is not the protected Settings page:
        //   - Home / another app (any non-Settings package)
        //   - one of our own non-gate windows (MainActivity, BlockActivity…)
        // It is NOT a departure when the window is:
        //   - an input-method/keyboard window (an overlay, not an app switch), or
        //   - the password gate itself (an overlay ON the protected page).
        // Another Settings screen is handled after page detection below.
        //
        // Detecting the departure edge (rather than relying on elapsed time) is
        // what makes Home → Recent Apps → resume the existing Settings task
        // re-arm reliably, even when the round trip completes in well under the
        // old 5s cooldown window.
        if (settingsProtectedPageActive &&
            isWindowStateChange &&
            hasLeftProtectedPage(packageName, event)) {
            settingsProtectedPageActive = false
            settingsAuthorizedVisit = false
            Log.i(SETTINGS_GUARD_TAG, "LEFT protected AI Guardian Accessibility page (pkg=$packageName)")
        }

        // AI Guardian's own windows can never BE the protected page. The leave
        // check above has already run, so an own-package window that is not the
        // password gate correctly counts as a departure.
        if (packageName == OWN_PACKAGE) return

        // Only process Settings window-state changes below this point.
        if (!isSettingsPackage(packageName)) return
        if (!isWindowStateChange) return

        // Is the protected page (AI Guardian's AccessibilityService details
        // page) foreground right now?
        if (!detectAiGuardianAccessibilityDetailsPage(event, packageName)) {
            // A DIFFERENT Settings screen (Wi-Fi/Display/Battery/Apps/list…) is
            // foreground, so the protected page is NOT. Clear the page context
            // so that navigating back to the protected page later is a fresh
            // ARRIVAL and re-arms the protection — without this, the page flag
            // stayed stale-true across within-Settings navigation and the return
            // visit took the same-visit path with no re-arm. This only clears
            // state: the guard is NEVER triggered here, so every other Settings
            // screen remains unrestricted.
            //
            // While the gate itself is on screen, the covered Settings window's
            // events are ambiguous — leave the visit state untouched so gate
            // dismissal can still mark the visit authorized (prevents a
            // password → immediate re-prompt loop).
            if (settingsProtectedPageActive && !settingsAccessibilityGuardActive) {
                settingsProtectedPageActive = false
                settingsAuthorizedVisit = false
                Log.i(SETTINGS_GUARD_TAG, "LEFT protected AI Guardian Accessibility page (other Settings screen pkg=$packageName)")
            }
            return
        }

        // Protected page is (again) foreground.
        if (!settingsProtectedPageActive) {
            // ARRIVAL EDGE: the protected page just became active. This covers
            // the Home → Recent Apps bypass directly — the previously-left page
            // is now active again, and because re-arm is edge-driven, it fires
            // regardless of how quickly the user came back.
            settingsProtectedPageActive = true
            settingsAuthorizedVisit = false
            Log.d(SETTINGS_GUARD_TAG, "On protected AI Guardian Accessibility page — rearmed")
            triggerSettingsAccessibilityGuard()
            return
        }

        // Still on the protected page (same visit — no leave edge).
        // Do NOT re-prompt on the repeated WINDOW_STATE_CHANGED events Settings
        // emits for the same page while the gate is still up (Home was pressed,
        // gate is backgrounded but alive and unauthenticated). The page-arrival
        // edge above is what re-arms; this is only a same-visit duplicate guard.
        if (settingsAccessibilityGuardActive) {
            Log.d(SETTINGS_GUARD_TAG, "GUARD_VISIBLE (same visit — no duplicate prompt)")
            return
        }

        // Still on the protected page after the gate was dismissed (password
        // accepted, or cancelled). Do NOT re-prompt on the same visit.
        if (settingsAuthorizedVisit) {
            Log.d(SETTINGS_GUARD_TAG, "GUARD_VISIBLE (authorized visit — no re-prompt)")
            return
        }

        // Last-resort safety net only: an emergency throttle for the extremely
        // unlikely case of the page being re-detected without a leave edge
        // (e.g. an event stream that announces the page repeatedly with no
        // intervening non-Settings window). It is NOT the re-arm mechanism.
        if (now - lastSettingsGuardTriggerTime < SETTINGS_GUARD_COOLDOWN_MS) {
            Log.d(SETTINGS_GUARD_TAG, "COOLDOWN (${now - lastSettingsGuardTriggerTime}ms < ${SETTINGS_GUARD_COOLDOWN_MS}ms)")
            return
        }

        Log.w(SETTINGS_GUARD_TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: DETECTED")
        triggerSettingsAccessibilityGuard()
    }

    /**
     * Check if the package is an Android/Samsung Settings package.
     */
    private fun isSettingsPackage(packageName: String): Boolean {
        return packageName == "com.android.settings" ||
            packageName == "com.sec.android.app.safetyassistance" ||
            packageName == "com.samsung.android.settings" ||
            packageName.contains("settings", ignoreCase = true)
    }

    /**
     * True when a window-state change means the protected AI Guardian
     * Accessibility page is no longer the foreground context.
     */
    private fun hasLeftProtectedPage(packageName: String, event: AccessibilityEvent): Boolean {
        // Our own password gate is an overlay ON the protected page — its
        // window appearing does not mean the user left the page. (Clearing the
        // page context there would make the gate dismissal skip the authorized
        // mark and re-prompt after every successful password entry.)
        if (packageName == OWN_PACKAGE) {
            return event.className?.toString() != SETTINGS_GUARD_ACTIVITY_CLASS
        }
        // Input-method (soft keyboard) windows overlay whatever is below them —
        // a keyboard appearing or hiding is not an app switch, so it must not
        // clear the page context while the protected page/gate is still shown.
        if (isImePackage(packageName)) {
            return false
        }
        // Any other non-Settings foreground package (Home launcher, another
        // app) is a real departure from the Settings page.
        return !isSettingsPackage(packageName)
    }

    /**
     * True for input-method (soft keyboard) window packages such as Samsung
     * Honeyboard, Gboard, or AOSP LatinIME.
     */
    private fun isImePackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p.contains("inputmethod") ||
            p.contains("honeyboard") ||
            p.contains("keyboard") ||
            p.contains("softkeyboard") ||
            p.endsWith(".ime")
    }

    /**
     * Detect whether the current window represents AI Guardian's
     * AccessibilityService details/management page in Settings.
     *
     * DETECTION RULE (narrow — requires AI Guardian identity):
     *
     *   hasAiGuardianName == true   (MANDATORY — the screen must mention AI Guardian)
     *   AND
     *   (hasA11yClass OR hasToggleNode)   (at least one accessibility context signal)
     *
     * Rationale: A generic Settings page with a toggle must NEVER trigger the guard.
     * Only a page that explicitly identifies as AI Guardian's accessibility page
     * is protected.
     */
    private fun detectAiGuardianAccessibilityDetailsPage(
        event: AccessibilityEvent,
        packageName: String,
    ): Boolean {
        // Collect visible text from multiple sources
        val eventText = event.text?.joinToString(" ") { it?.toString() ?: "" } ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Root node of THE WINDOW THIS EVENT IS ABOUT.
        //
        // Prefer event.source over rootInActiveWindow: for a window-state event
        // the source is bound to the window that changed state (its window id is
        // recorded when the event is created). When Android resumes an EXISTING
        // Settings task from Recent Apps — no new Activity, no onCreate — this
        // is the node that reliably points at the resumed window: by the time
        // our process handles that single resume event, rootInActiveWindow can
        // still be the previous (launcher/Recents) window or null, which made
        // page detection fail on exactly the Home → Recents → return transition
        // and permanently skipped the re-arm for it.
        val windowRoot: AccessibilityNodeInfo? = try {
            event.source
        } catch (_: Exception) {
            null
        } ?: try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        try {
            // Flattened text summary of the event's own window (same bounds and
            // format as the previous rootInActiveWindow-based summary).
            val windowTextSummary = try {
                if (windowRoot == null) {
                    "(no root)"
                } else {
                    val sb = StringBuilder(SETTINGS_DIAG_FLATTEN_MAX_CHARS)
                    collectSettingsDiagNodeText(windowRoot, 0, sb)
                    val joined = sb.toString().trim()
                    if (joined.length > SETTINGS_DIAG_FLATTEN_MAX_CHARS) {
                        joined.substring(0, SETTINGS_DIAG_FLATTEN_MAX_CHARS) + "..."
                    } else {
                        joined.ifEmpty { "(empty)" }
                    }
                }
            } catch (_: Exception) {
                "(error)"
            }

            val allVisibleText = "$eventText $contentDesc $windowTextSummary"

            // MANDATORY: "AI Guardian" text visible — this is the identity signal.
            // Without this, the screen is NOT about AI Guardian and must NOT be protected.
            val hasAiGuardianName = allVisibleText.contains("AI Guardian", ignoreCase = true) ||
                allVisibleText.contains("aiguardian", ignoreCase = true) ||
                allVisibleText.contains("ai_guardian", ignoreCase = true)

            // If AI Guardian name is not visible, this is NOT the AI Guardian page.
            if (!hasAiGuardianName) {
                return false
            }

            // AI Guardian name IS visible — now confirm accessibility context.
            // Signal A: Accessibility-related class/activity name
            val a11yKeywords = listOf(
                "accessibility", "accessibilityservice", "accessibility_settings",
                "accessibility details", "accessibility service"
            )
            val allClassText = "$className".lowercase()
            val hasA11yClass = a11yKeywords.any { allClassText.contains(it) }

            // Signal B: Toggle/switch node present (the enable/disable control),
            // searched in the event's own window root.
            val hasToggleNode = try {
                findToggleNode(windowRoot, 0)
            } catch (_: Exception) {
                false
            }

            val hasA11yContext = hasA11yClass || hasToggleNode

            // Log detection result
            Log.i(SETTINGS_DIAG_TAG, "SETTINGS_A11Y_DETECT: " +
                "aiGuardian=$hasAiGuardianName a11yClass=$hasA11yClass toggle=$hasToggleNode " +
                "context=$hasA11yContext pkg=$packageName cls=$className")

            // FINAL: AI Guardian name MANDATORY + accessibility context
            return hasAiGuardianName && hasA11yContext
        } finally {
            try {
                windowRoot?.recycle()
            } catch (_: Exception) { /* already recycled */ }
        }
    }

    /**
     * Recursively search the node tree for a Switch or CheckBox widget,
     * which indicates the AccessibilityService enable/disable toggle.
     */
    private fun findToggleNode(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 10) return false

        val className = node.className?.toString()?.lowercase() ?: ""
        val isToggle = className.contains("switch") || className.contains("checkbox") ||
            className.contains("toggle")

        if (isToggle) return true

        val childCount = node.childCount
        for (i in 0 until childCount) {
            try {
                if (findToggleNode(node.getChild(i), depth + 1)) return true
            } catch (_: Exception) { /* skip */ }
        }

        return false
    }

    /**
     * Trigger the settings accessibility guard: show the password protection
     * gate ON TOP of the current Settings screen.
     *
     * IMPORTANT: We do NOT call GLOBAL_ACTION_BACK here.
     * The BACK action was destroying the target Settings details screen,
     * causing the user to be returned to the Installed apps list instead
     * of the AI Guardian toggle page after authentication.
     *
     * Instead, we show the guard activity on top. The underlying Settings
     * task (with the target details screen) remains in its current state.
     * When the guard finishes, the target screen is revealed.
     *
     * Steps:
     * 1. Set guard active (prevent re-triggering)
     * 2. Log the detection
     * 3. Launch the SettingsAccessibilityGuardActivity password gate
     *    (NO BACK action — the target screen stays underneath)
     */
    private fun triggerSettingsAccessibilityGuard() {
        // Set guard active. While this is true, further events are ignored
        // (see the top of checkSettingsAccessibilityGuard), so no second popup
        // can appear while the gate is on screen. The gate clears this flag
        // in clearSettingsAccessibilityGuard() when it is destroyed.
        settingsAccessibilityGuardActive = true
        lastSettingsGuardTriggerTime = System.currentTimeMillis()

        Log.w(SETTINGS_GUARD_TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: REARMED/DETECTED")

        // Launch the password protection gate ON TOP of the Settings target screen.
        // We do NOT call GLOBAL_ACTION_BACK — the target screen must remain
        // underneath so it is revealed when the guard finishes.
        try {
            SettingsAccessibilityGuardActivity.launch(applicationContext)
            Log.i(SETTINGS_GUARD_TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: PASSWORD_GATE_SHOWN")
        } catch (e: Exception) {
            Log.e(SETTINGS_GUARD_TAG, "Failed to launch SettingsAccessibilityGuardActivity: ${e.message}")
        }
    }

    /**
     * Called by SettingsAccessibilityGuardActivity when the password gate is dismissed
     * (correct password, wrong password retries, or cancel/back).
     * Clears the guard active state so future detection can work.
     */
    fun clearSettingsAccessibilityGuard() {
        // The gate was dismissed. If the protected page is still the foreground
        // context, mark the visit as authorized so the guard does not immediately
        // re-prompt on the same visit. The flag is reset the moment the user
        // leaves the page (or on the next arrival), so the protection is fully
        // re-armed afterwards — Home → Recents → returning to the page prompts
        // again.
        if (settingsProtectedPageActive) {
            settingsAuthorizedVisit = true
        }
        settingsAccessibilityGuardActive = false
        Log.i(SETTINGS_GUARD_TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: GUARD_CLEARED (authorizedVisit=$settingsAuthorizedVisit)")
    }

    /**
     * Flatten the Settings window's accessibility node tree into a bounded string.
     * Used for diagnostic log lines and detection — NOT for content filtering.
     */
    private fun getSettingsDiagWindowSummary(): String {
        return try {
            val root = rootInActiveWindow ?: return "(no root)"
            val sb = StringBuilder(SETTINGS_DIAG_FLATTEN_MAX_CHARS)
            collectSettingsDiagNodeText(root, 0, sb)
            root.recycle()
            val joined = sb.toString().trim()
            if (joined.length > SETTINGS_DIAG_FLATTEN_MAX_CHARS) {
                joined.substring(0, SETTINGS_DIAG_FLATTEN_MAX_CHARS) + "..."
            } else {
                joined.ifEmpty { "(empty)" }
            }
        } catch (_: Exception) {
            "(error)"
        }
    }

    /**
     * Recursively collect text from a node tree for Settings diagnostic summarisation.
     */
    private fun collectSettingsDiagNodeText(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 8) return
        if (sb.length > SETTINGS_DIAG_FLATTEN_MAX_CHARS) return

        val t = node.text
        if (t != null && t.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }

        val d = node.contentDescription
        if (d != null && d.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("[desc:$d]")
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            try {
                collectSettingsDiagNodeText(node.getChild(i), depth + 1, sb)
            } catch (_: Exception) { /* skip */ }
            if (sb.length > SETTINGS_DIAG_FLATTEN_MAX_CHARS) break
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SETTINGS ACCESSIBILITY DIAGNOSTIC (detection only, no action)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Diagnostic logger for Settings accessibility events.
     *
     * Logs accessibility events from Settings packages to discover the exact
     * screen structure when the user navigates to AI Guardian's accessibility
     * management page. This is diagnostic-only — no blocking, no action taken.
     *
     * Uses tag `AI_GUARDIAN_ACCESSIBILITY_SETTINGS_DIAG` — capture via:
     *
     *     adb logcat -s AI_GUARDIAN_ACCESSIBILITY_SETTINGS_DIAG:V
     *
     * Bounded by [SETTINGS_DIAG_MAX_EVENTS] to prevent logcat flooding.
     */
    private fun logSettingsAccessibilityDiagnostic(event: AccessibilityEvent) {
        if (settingsDiagEventCount >= SETTINGS_DIAG_MAX_EVENTS) return

        val packageName = event.packageName?.toString() ?: return

        // Only process Settings packages
        if (!isSettingsPackage(packageName)) return

        val eventType = event.eventType
        val className = event.className?.toString() ?: "(null)"
        val eventText = event.text?.joinToString("|") { it?.toString() ?: "" } ?: "(null)"
        val contentDesc = event.contentDescription?.toString() ?: "(null)"

        // Source node info (best-effort, never crash)
        var sourceNodeClass: String = "(null)"
        var sourceNodeText: String = "(null)"
        var sourceNodeDesc: String = "(null)"
        try {
            val src = event.source
            if (src != null) {
                sourceNodeClass = src.className?.toString() ?: "(null)"
                sourceNodeText = src.text?.toString() ?: "(null)"
                sourceNodeDesc = src.contentDescription?.toString() ?: "(null)"
                src.recycle()
            }
        } catch (_: Exception) { /* source unavailable */ }

        // Active window info (best-effort, may be null)
        var activeWindowPkg: String = "(null)"
        var activeWindowCls: String = "(null)"
        try {
            val root = rootInActiveWindow
            if (root != null) {
                activeWindowPkg = root.packageName?.toString() ?: "(null)"
                activeWindowCls = root.className?.toString() ?: "(null)"
                root.recycle()
            }
        } catch (_: Exception) { /* unavailable */ }

        // Flattened text summary — only for first few events to avoid overhead
        val windowTextSummary = if (settingsDiagEventCount < 5) {
            try { getSettingsDiagWindowSummary() } catch (_: Exception) { "(error)" }
        } else {
            "(skipped after initial events)"
        }

        settingsDiagEventCount++

        // Main diagnostic log line — only first few events
        if (settingsDiagEventCount < 5) {
            Log.i(SETTINGS_DIAG_TAG, "[event #$settingsDiagEventCount] " +
                "type=$eventType " +
                "pkg=$packageName " +
                "cls=$className " +
                "text=$eventText " +
                "desc=$contentDesc " +
                "srcCls=$sourceNodeClass " +
                "srcText=$sourceNodeText " +
                "srcDesc=$sourceNodeDesc " +
                "winPkg=$activeWindowPkg " +
                "winCls=$activeWindowCls")
        }

        // Log window summary for first few events (to discover screen structure)
        if (settingsDiagEventCount <= 3 && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(SETTINGS_DIAG_TAG, "[event #$settingsDiagEventCount windowSummary] $windowTextSummary")
        }

        // Detect and log when AI Guardian text appears in Settings
        val allVisibleText = "$eventText $contentDesc $sourceNodeText $sourceNodeDesc $windowTextSummary"
        if (allVisibleText.contains("AI Guardian", ignoreCase = true)) {
            Log.i(SETTINGS_DIAG_TAG, "AI_GUARDIAN_TEXT_FOUND_IN_SETTINGS: " +
                "pkg=$packageName cls=$className " +
                "winPkg=$activeWindowPkg winCls=$activeWindowCls " +
                "text=$eventText desc=$contentDesc")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  BROWSER WEBSITE DIAGNOSTIC (Phase 3A — detection only, no action)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Diagnostic logger for browser website detection.
     *
     * Logs accessibility events from browser packages (primarily Chrome) to
     * discover whether the actual URL/domain is exposed through the accessibility
     * tree. This is diagnostic-only — no blocking, no action taken.
     *
     * Uses tag `AI_GUARDIAN_WEB_DIAG` — capture via:
     *
     *     adb logcat -s AI_GUARDIAN_WEB_DIAG:V
     *
     * Bounded by [WEB_DIAG_MAX_EVENTS] to prevent logcat flooding.
     */
    private fun logBrowserDiagnostic(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Only process browser packages
        if (packageName !in BROWSER_PACKAGES) return

        // ── Functional blocking: ALWAYS runs, NOT gated by diagnostic counter ──
        // Extract text sources needed for URL detection and blocking
        val eventType = event.eventType
        val eventText = event.text?.joinToString("|") { it?.toString() ?: "" } ?: "(null)"
        val contentDesc = event.contentDescription?.toString() ?: "(null)"

        var sourceNodeText: String = "(null)"
        var sourceNodeDesc: String = "(null)"
        try {
            val src = event.source
            if (src != null) {
                sourceNodeText = src.text?.toString() ?: "(null)"
                sourceNodeDesc = src.contentDescription?.toString() ?: "(null)"
                src.recycle()
            }
        } catch (_: Exception) { /* source unavailable */ }

        // Get window text summary for blocking decisions (always needed)
        val windowTextSummary = try { getWebDiagWindowSummary() } catch (_: Exception) { "(error)" }

        // Website blocking — runs for EVERY browser event, not limited by diagnostic counter
        detectUrlCandidates(packageName, eventText, contentDesc, sourceNodeText, sourceNodeDesc, windowTextSummary)

        // ── Diagnostic logging: gated by counter to prevent logcat flooding ──
        if (webDiagEventCount >= WEB_DIAG_MAX_EVENTS) return

        val className = event.className?.toString() ?: "(null)"

        // Source node info for diagnostics (best-effort, never crash)
        var sourceNodeClass: String = "(null)"
        try {
            val src = event.source
            if (src != null) {
                sourceNodeClass = src.className?.toString() ?: "(null)"
                src.recycle()
            }
        } catch (_: Exception) { /* source unavailable */ }

        // Active window info (best-effort, may be null)
        var activeWindowPkg: String = "(null)"
        var activeWindowCls: String = "(null)"
        try {
            val root = rootInActiveWindow
            if (root != null) {
                activeWindowPkg = root.packageName?.toString() ?: "(null)"
                activeWindowCls = root.className?.toString() ?: "(null)"
                root.recycle()
            }
        } catch (_: Exception) { /* unavailable */ }

        // Bounded window text summary from node tree — only for first few events to avoid overhead
        val diagWindowSummary = if (webDiagEventCount < 5) {
            windowTextSummary
        } else {
            "(skipped after initial events)"
        }

        webDiagEventCount++

        // Log browser window detection on window state changes
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val isNewBrowser = packageName != lastBrowserPackage
            lastBrowserPackage = packageName

            Log.i(WEB_DIAG_TAG, "BROWSER_WINDOW package=$packageName class=$className " +
                "windowPkg=$activeWindowPkg windowCls=$activeWindowCls " +
                "newBrowser=$isNewBrowser")

            // Log bounded visible accessibility summary for page content discovery
            Log.i(WEB_DIAG_TAG, "[event #$webDiagEventCount windowSummary] $diagWindowSummary")
        }

        // Log full event metadata for text/content changes (where URLs may appear)
        // Only log first few events to avoid logcat flooding
        if (webDiagEventCount < 5 &&
            (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
             eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {

            Log.i(WEB_DIAG_TAG, "[event #$webDiagEventCount] " +
                "type=$eventType " +
                "pkg=$packageName " +
                "cls=$className " +
                "text=$eventText " +
                "desc=$contentDesc " +
                "srcCls=$sourceNodeClass " +
                "srcText=$sourceNodeText " +
                "srcDesc=$sourceNodeDesc " +
                "winPkg=$activeWindowPkg " +
                "winCls=$activeWindowCls")
        }
    }

    /**
     * Scan visible text for URL/domain candidates and log them with source attribution.
     *
     * Looks for:
     * - text starting with https:// or http://
     * - text starting with www.
     * - domain-like strings (e.g. example.com)
     * - content descriptions containing a URL
     *
     * Deduplicates identical candidates within [WEB_DIAG_URL_COOLDOWN_MS].
     * Does NOT invent or assume URLs — only logs what is actually present.
     * Does NOT log passwords, credentials, or sensitive form data.
     *
     * Phase 3B: When the source package is Chrome, also checks candidates
     * against blocked domains and triggers blocking if matched.
     */
    private fun detectUrlCandidates(
        packageName: String,
        eventText: String,
        contentDesc: String,
        sourceNodeText: String,
        sourceNodeDesc: String,
        windowTextSummary: String,
    ) {
        val now = System.currentTimeMillis()

        // Check each text source for URL candidates (logging only)
        checkTextForUrl(eventText, "event_text", now)
        checkTextForUrl(contentDesc, "content_description", now)
        checkTextForUrl(sourceNodeText, "node_text", now)
        checkTextForUrl(sourceNodeDesc, "node_content_description", now)
        checkTextForUrl(windowTextSummary, "window_summary", now)

        // Phase 3B: Website blocking — only for Chrome, using full window context
        if (packageName == PACKAGE_CHROME && blockedDomains.isNotEmpty()) {
            val allText = "$eventText $contentDesc $sourceNodeText $sourceNodeDesc $windowTextSummary"
            val candidates = extractUrlCandidates(allText)
            for (candidate in candidates) {
                handleWebBlockCandidate(candidate, eventText, sourceNodeText, windowTextSummary)
            }
        }
    }

    /**
     * Check a single text string for URL/domain patterns and log any found.
     * Deduplicates within cooldown period.
     * Phase 3B: Blocking is handled separately in detectUrlCandidates with full context.
     */
    private fun checkTextForUrl(text: String, source: String, now: Long) {
        if (text.isEmpty() || text == "(null)") return

        // Extract URL candidates from the text
        val candidates = extractUrlCandidates(text)

        for (candidate in candidates) {
            val normalized = candidate.lowercase().trim().trimEnd('.', ',', ')', ']', '>', '"', '\'')

            // Dedup check
            val lastSeen = webDiagUrlTimestamps[normalized]
            if (lastSeen != null && (now - lastSeen) < WEB_DIAG_URL_COOLDOWN_MS) {
                continue
            }

            webDiagUrlTimestamps[normalized] = now
            // Only log first few URL candidates
            if (webDiagUrlTimestamps.size <= 5) {
                Log.i(WEB_DIAG_TAG, "AI_GUARDIAN_WEB_DIAG: URL_CANDIDATE=$candidate")
            }
        }

        // Cleanup old entries periodically (every 100 events)
        if (webDiagEventCount % 100 == 0 && webDiagUrlTimestamps.size > 50) {
            val cutoff = now - WEB_DIAG_URL_COOLDOWN_MS * 2
            webDiagUrlTimestamps.entries.removeAll { it.value < cutoff }
        }
    }

    /**
     * Extract URL-like candidates from a text string.
     *
     * Patterns detected:
     * - Full URLs: https://..., http://...
     * - www-prefixed: www.example.com
     * - Domain-like: words.tld (e.g. example.com, test.org)
     *
     * Returns raw matched substrings — normalisation for comparison only,
     * original text preserved in logs.
     */
    private fun extractUrlCandidates(text: String): List<String> {
        val results = mutableListOf<String>()

        // Pattern 1: Full URLs (http:// or https://) — pre-compiled
        for (match in REGEX_FULL_URL.findAll(text)) {
            results.add(match.value)
        }

        // Pattern 2: www. prefixed domains — pre-compiled
        for (match in REGEX_WWW.findAll(text)) {
            // Only add if not already captured by full URL pattern
            if (results.none { it.contains(match.value, ignoreCase = true) }) {
                results.add(match.value)
            }
        }

        // Pattern 3: domain.tld (without http/www prefix) — pre-compiled
        for (match in REGEX_DOMAIN.findAll(text)) {
            val candidate = match.value
            // Skip if already captured, or if it's a common non-domain word
            if (results.any { it.contains(candidate, ignoreCase = true) }) continue
            if (candidate.length < 5) continue // too short to be a real domain
            // Skip common false positives
            val lower = candidate.lowercase()
            if (lower.startsWith("android.") || lower.startsWith("com.") ||
                lower.endsWith(".xml") || lower.endsWith(".json")) continue
            results.add(candidate)
        }

        return results
    }

    /**
     * Flatten browser window's accessibility node tree into a bounded string.
     * Used for diagnostic log lines — NOT for content filtering.
     * Bounded to [WEB_DIAG_MAX_NODE_DEPTH] levels and [WEB_DIAG_FLATTEN_MAX_CHARS] chars.
     */
    private fun getWebDiagWindowSummary(): String {
        return try {
            val root = rootInActiveWindow ?: return "(no root)"
            val sb = StringBuilder(WEB_DIAG_FLATTEN_MAX_CHARS)
            collectWebDiagNodeText(root, 0, sb)
            root.recycle()
            val joined = sb.toString().trim()
            if (joined.length > WEB_DIAG_FLATTEN_MAX_CHARS) {
                joined.substring(0, WEB_DIAG_FLATTEN_MAX_CHARS) + "…"
            } else {
                joined.ifEmpty { "(empty)" }
            }
        } catch (_: Exception) {
            "(error)"
        }
    }

    /**
     * Recursively collect text from a node tree for web diagnostic summarisation.
     * Bounded to [WEB_DIAG_MAX_NODE_DEPTH] levels deep and [WEB_DIAG_FLATTEN_MAX_CHARS] chars.
     */
    private fun collectWebDiagNodeText(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > WEB_DIAG_MAX_NODE_DEPTH) return
        if (sb.length > WEB_DIAG_FLATTEN_MAX_CHARS) return

        val t = node.text
        if (t != null && t.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }

        val d = node.contentDescription
        if (d != null && d.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("[desc:$d]")
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            try {
                collectWebDiagNodeText(node.getChild(i), depth + 1, sb)
            } catch (_: Exception) { /* skip */ }
            if (sb.length > WEB_DIAG_FLATTEN_MAX_CHARS) break
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  UNINSTALL-FLOW DIAGNOSTIC LAYER (Phase 1 — detection only, no action)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Diagnostic logger for the uninstall-flow detection experiment.
     *
     * Logs every accessibility event with full metadata under the tag
     * `AI_GUARDIAN_UNINSTALL_DIAG` so it can be captured via:
     *
     *     adb logcat -s AI_GUARDIAN_UNINSTALL_DIAG:V
     *
     * Also detects and emits a separate UNINSTALL_FLOW_DETECTED message when the
     * visible window appears to represent an uninstall flow for AI Guardian.
     * Detection uses multiple signals (package, class, text, content description)
     * and NEVER takes action — it only logs.
     *
     * Bounded by [DIAG_MAX_EVENTS] to prevent logcat flooding.
     */
    private fun logUninstallDiagnostic(event: AccessibilityEvent) {
        if (diagEventCount >= DIAG_MAX_EVENTS) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: "(null)"
        val className = event.className?.toString() ?: "(null)"
        val eventText = event.text?.joinToString("|") { it?.toString() ?: "" } ?: "(null)"
        val contentDesc = event.contentDescription?.toString() ?: "(null)"

        // Source node info (may be null — never crash)
        var sourceNodeClass: String = "(null)"
        var sourceNodeText: String = "(null)"
        var sourceNodeDesc: String = "(null)"
        try {
            val src = event.source
            if (src != null) {
                sourceNodeClass = src.className?.toString() ?: "(null)"
                sourceNodeText = src.text?.toString() ?: "(null)"
                sourceNodeDesc = src.contentDescription?.toString() ?: "(null)"
                src.recycle()
            }
        } catch (_: Exception) { /* source unavailable */ }

        // Active window info (best-effort, may be null)
        var activeWindowPkg: String = "(null)"
        var activeWindowCls: String = "(null)"
        try {
            val root = rootInActiveWindow
            if (root != null) {
                activeWindowPkg = root.packageName?.toString() ?: "(null)"
                activeWindowCls = root.className?.toString() ?: "(null)"
                root.recycle()
            }
        } catch (_: Exception) { /* unavailable */ }

        // Flattened text summary of the active window — only for first few events to avoid overhead
        val windowTextSummary = if (diagEventCount < 5) {
            try { getFlattenedWindowTextSummary() } catch (_: Exception) { "(error)" }
        } else {
            "(skipped after initial events)"
        }

        diagEventCount++

        // ── Main diagnostic log line — only first few events ──
        if (diagEventCount < 5) {
            Log.i(DIAG_TAG, "[event #$diagEventCount] " +
                "type=$eventType " +
                "pkg=$packageName " +
                "cls=$className " +
                "text=$eventText " +
                "desc=$contentDesc " +
                "srcCls=$sourceNodeClass " +
                "srcText=$sourceNodeText " +
                "srcDesc=$sourceNodeDesc " +
                "winPkg=$activeWindowPkg " +
                "winCls=$activeWindowCls")
        }

        // ── Uninstall-flow detection (log-only, no action) ──
        detectUninstallFlow(eventType, packageName, className, eventText, contentDesc,
            sourceNodeClass, sourceNodeText, sourceNodeDesc,
            activeWindowPkg, activeWindowCls, windowTextSummary)
    }

    /**
     * Detect whether the currently visible window represents an uninstall flow
     * for AI Guardian. Uses multiple signals — never acts on the result.
     */
    private fun detectUninstallFlow(
        eventType: Int,
        packageName: String,
        className: String,
        eventText: String,
        contentDesc: String,
        sourceNodeClass: String,
        sourceNodeText: String,
        sourceNodeDesc: String,
        activeWindowPkg: String,
        activeWindowCls: String,
        windowTextSummary: String,
    ) {
        // ── Signal 1: package name matches Android Settings ──
        val isSettingsPkg = packageName == "com.android.settings" ||
            packageName == "com.sec.android.app.safetyassurance" ||
            packageName.contains("settings", ignoreCase = true)

        // ── Signal 2: package is AI Guardian itself (user may be inside uninstall dialog) ──
        val isOwnPkg = packageName == OWN_PACKAGE

        // ── Signal 3: class name contains uninstall-related terms ──
        val uninstallKeywords = listOf("uninstall", "remove", "disable", "appinfo", "app_info", "packageinstaller")
        val allClassText = "$className $activeWindowCls $sourceNodeClass".lowercase()
        val hasUninstallClass = uninstallKeywords.any { allClassText.contains(it) }

        // ── Signal 4: visible text contains AI Guardian identifiers ──
        val allVisibleText = "$eventText $contentDesc $sourceNodeText $sourceNodeDesc $windowTextSummary"
        val hasAiGuardianName = allVisibleText.contains("AI Guardian", ignoreCase = true) ||
            allVisibleText.contains("aiguardian", ignoreCase = true) ||
            allVisibleText.contains("ai_guardian", ignoreCase = true)

        // ── Signal 5: visible text contains uninstall-related strings ──
        val uninstallTextKeywords = listOf(
            "uninstall", "uninstall app", "do you want to uninstall",
            "remove", "remove app", "this app will be uninstalled",
            "disable", "force stop"
        )
        val hasUninstallText = uninstallTextKeywords.any {
            allVisibleText.lowercase().contains(it)
        }

        // ── Composite detection: log when multiple signals agree ──
        val signalCount = listOf(isSettingsPkg, isOwnPkg, hasUninstallClass,
            hasAiGuardianName, hasUninstallText).count { it }

        if (signalCount >= 2) {
            Log.w(DIAG_TAG, "*** UNINSTALL_FLOW_DETECTED (signals=$signalCount) *** " +
                "settingsPkg=$isSettingsPkg ownPkg=$isOwnPkg uninstallClass=$hasUninstallClass " +
                "aiGuardianName=$hasAiGuardianName uninstallText=$hasUninstallText " +
                "activeWin=$activeWindowPkg/$activeWindowCls")
        }
    }

    /**
     * Flatten the text from the active window's node tree into a bounded string.
     * Used for diagnostic log lines only — NOT for content filtering.
     */
    private fun getFlattenedWindowTextSummary(): String {
        return try {
            val root = rootInActiveWindow ?: return "(no root)"
            val sb = StringBuilder(DIAG_FLATTEN_MAX_CHARS)
            collectNodeText(root, 0, sb)
            root.recycle()
            val joined = sb.toString().trim()
            if (joined.length > DIAG_FLATTEN_MAX_CHARS) {
                joined.substring(0, DIAG_FLATTEN_MAX_CHARS) + "…"
            } else {
                joined.ifEmpty { "(empty)" }
            }
        } catch (_: Exception) {
            "(error)"
        }
    }

    /**
     * Recursively collect text from a node tree for diagnostic summarisation.
     * Bounded to 5 levels deep and stops when the accumulated text exceeds the limit.
     */
    private fun collectNodeText(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 5) return
        if (sb.length > DIAG_FLATTEN_MAX_CHARS) return

        val t = node.text
        if (t != null && t.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }

        val d = node.contentDescription
        if (d != null && d.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("[desc:$d]")
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            try {
                collectNodeText(node.getChild(i), depth + 1, sb)
            } catch (_: Exception) { /* skip */ }
            if (sb.length > DIAG_FLATTEN_MAX_CHARS) break
        }
    }

    /**
     * Clean up stale entries from debounce/timestamp maps to prevent unbounded growth.
     * Called periodically (every 500 events) to keep maps bounded.
     */
    private fun cleanupStaleEntries() {
        val now = System.currentTimeMillis()
        val cutoff = now - DEBOUNCE_STALE_TTL_MS

        // Clean content filter debounce map
        if (contentFilterBlockTimes.size > MAX_DEBOUNCE_MAP_SIZE) {
            contentFilterBlockTimes.entries.removeAll { it.value < cutoff }
            // If still too large, force-evict oldest
            if (contentFilterBlockTimes.size > MAX_DEBOUNCE_MAP_SIZE) {
                val sorted = contentFilterBlockTimes.entries.sortedBy { it.value }
                val toRemove = sorted.take(sorted.size - MAX_DEBOUNCE_MAP_SIZE / 2)
                toRemove.forEach { contentFilterBlockTimes.remove(it.key) }
            }
        }

        // Clean web block debounce map
        if (webBlockDomainTimestamps.size > MAX_DEBOUNCE_MAP_SIZE) {
            webBlockDomainTimestamps.entries.removeAll { it.value < cutoff }
            if (webBlockDomainTimestamps.size > MAX_DEBOUNCE_MAP_SIZE) {
                val sorted = webBlockDomainTimestamps.entries.sortedBy { it.value }
                val toRemove = sorted.take(sorted.size - MAX_DEBOUNCE_MAP_SIZE / 2)
                toRemove.forEach { webBlockDomainTimestamps.remove(it.key) }
            }
        }

        // Clean web diagnostic URL timestamps map
        if (webDiagUrlTimestamps.size > MAX_DEBOUNCE_MAP_SIZE) {
            webDiagUrlTimestamps.entries.removeAll { it.value < cutoff }
            if (webDiagUrlTimestamps.size > MAX_DEBOUNCE_MAP_SIZE) {
                val sorted = webDiagUrlTimestamps.entries.sortedBy { it.value }
                val toRemove = sorted.take(sorted.size - MAX_DEBOUNCE_MAP_SIZE / 2)
                toRemove.forEach { webDiagUrlTimestamps.remove(it.key) }
            }
        }

        Log.d(TAG, "AI_GUARDIAN_SERVICE_HEALTH: Maps cleaned — contentFilter=${contentFilterBlockTimes.size} webBlock=${webBlockDomainTimestamps.size} webDiag=${webDiagUrlTimestamps.size}")
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
        diagEventCount = 0
        uninstallGuardActive = false
        lastGuardTriggerTime = 0L
        contentFilterBlockTimes.clear()
        webDiagEventCount = 0
        webDiagUrlTimestamps.clear()
        lastBrowserPackage = null
        blockedDomains.clear()
        webBlockDomainTimestamps.clear()
        lastBlockedWebDomain = null
        settingsDiagEventCount = 0
        settingsAccessibilityGuardActive = false
        settingsProtectedPageActive = false
        settingsAuthorizedVisit = false
        lastSettingsGuardTriggerTime = 0L
        Log.i(TAG, "AI_GUARDIAN_SERVICE_HEALTH: onDestroy — " +
            "contentRules=${contentFilterEngine?.getEnabledRuleCount() ?: 0} " +
            "cacheGen=$cacheGeneration")
        Log.i(TAG, "AI Guardian AccessibilityService destroyed")
        super.onDestroy()
    }

    fun notifyPolicyChanged(packageName: String) {
        foregroundMonitor?.onPolicyChanged(packageName)
        // Refresh policy cache so subsequent evaluate() calls use fresh data
        try {
            policyEngine?.refreshCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh policy cache on change: ${e.message}")
        }
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
            // Fail-safe: only replace rules if we got a non-empty result.
            // A DB error returns emptyList() — we must NOT destroy a valid in-memory cache.
            if (rules.isNotEmpty()) {
                engine.updateRules(rules)
                lastContentFilterLoadTime = System.currentTimeMillis()
                cacheGeneration++
                Log.i(TAG, "AI_GUARDIAN_SERVICE_HEALTH: Content filter rules refreshed: ${rules.size} enabled (gen=$cacheGeneration)")
            } else {
                // DB returned empty — log but KEEP previous valid rules
                Log.w(TAG, "AI_GUARDIAN_SERVICE_HEALTH: Content filter refresh returned 0 rules — keeping previous cache (${engine.getEnabledRuleCount()} rules)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI_GUARDIAN_SERVICE_ERROR: stage=refreshContentFilterRules exception=${e.javaClass.simpleName} message=${e.message}")
        }
        // Also refresh policy cache (CRUD from UI may have changed policies)
        try {
            policyEngine?.refreshCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh policy cache: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PHASE 3B: WEBSITE BLOCKING (AccessibilityService-based)
    //
    //  State-based detection: only blocks when Chrome is confirmed to be
    //  displaying an actual web page (not new-tab, not address bar, not
    //  search suggestions, not bookmarks/history).
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Chrome page states used for blocking decisions.
     */
    private enum class ChromePageState {
        /** Chrome is showing the new-tab / homepage / shortcut grid. */
        NEW_TAB,
        /** Chrome's address/omnibox bar is focused (user typing). */
        ADDRESS_BAR,
        /** Chrome is showing a search results page (Google or other). */
        SEARCH_RESULTS,
        /** Chrome is displaying an actual loaded web page (normal mode). */
        WEB_PAGE,
        /** Chrome Incognito is displaying an actual loaded web page (no WebView signals). */
        INCOGNITO_WEB_PAGE,
        /** State could not be determined — conservative: do not block. */
        UNKNOWN,
    }

    /**
     * Refresh the in-memory blocked domains set from the database.
     * Called when domains are added, deleted, or toggled from Flutter.
     */
    fun refreshBlockedDomains() {
        try {
            val domainRepo = DomainRepository(PolicyDatabaseHelper(applicationContext))
            val domainSet = domainRepo.getBlockedDomainSet()
            // Fail-safe: build new set first, then atomically replace.
            // A DB error returns empty set — we must NOT destroy a valid in-memory cache.
            if (domainSet.isNotEmpty()) {
                blockedDomains.clear()
                blockedDomains.addAll(domainSet)
                lastBlockedDomainsLoadTime = System.currentTimeMillis()
                cacheGeneration++
                Log.i(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_HEALTH: Blocked domains refreshed: ${blockedDomains.size} active (gen=$cacheGeneration)")
            } else {
                // DB returned empty — log but KEEP previous valid cache
                Log.w(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_HEALTH: Domain refresh returned 0 domains — keeping previous cache (${blockedDomains.size} domains)")
            }
        } catch (e: Exception) {
            Log.e(WEB_BLOCK_TAG, "AI_GUARDIAN_SERVICE_ERROR: stage=refreshBlockedDomains exception=${e.javaClass.simpleName} message=${e.message}")
        }
        // Also refresh policy cache (CRUD from UI may have changed policies)
        try {
            policyEngine?.refreshCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh policy cache: ${e.message}")
        }
    }

    /**
     * Check whether a detected domain candidate matches any blocked domain.
     *
     * Matching rules:
     * - Exact match: "facebook.com" matches "facebook.com"
     * - Subdomain match: "m.facebook.com" matches "facebook.com"
     * - Subdomain match: "www.facebook.com" matches "facebook.com"
     * - Non-match: "facebook.com.example.com" does NOT match "facebook.com"
     * - Non-match: "notfacebook.com" does NOT match "facebook.com"
     *
     * The candidate is normalized (lowercase, strip protocol/path/www.)
     * before comparison.
     *
     * Returns the matching blocked domain, or null if no match.
     */
    private fun matchBlockedDomain(candidate: String): String? {
        if (blockedDomains.isEmpty()) return null

        val normalized = DomainPolicy.normalize(candidate) ?: return null

        // Exact match
        if (normalized in blockedDomains) {
            return normalized
        }

        // Subdomain match: check if candidate is a subdomain of any blocked domain
        for (blocked in blockedDomains) {
            if (normalized.endsWith(".$blocked")) {
                return blocked
            }
        }

        return null
    }

    /**
     * Detect Chrome's current page state from the accessibility tree.
     *
     * This is the core logic that determines whether blocking should occur.
     * Only [ChromePageState.WEB_PAGE] allows blocking.
     *
     * Detection signals (checked in priority order):
     *
     * 1. NEW_TAB — page contains:
     *    - "Search Google or type URL" (omnibox placeholder)
     *    - Shortcuts heading ("Shortcuts")
     *    - Edit-shortcut buttons (aria-label pattern)
     *    - "Customise and control Google Chrome" (three-dot menu)
     *    WITHOUT any WebView or security indicators
     *
     * 2. ADDRESS_BAR — page contains:
     *    - "Edit shortcut" (shortcut edit mode)
     *    - "Recently visited" (dropdown suggestions)
     *    - "Trending searches" (search suggestions)
     *    - "See tab groups" / "Turn on Incognito" / "New tab" (menu items)
     *    - URL bar focused with autocomplete visible
     *
     * 3. SEARCH_RESULTS — page contains:
     *    - "Search results" or "results" near search context
     *    - "People also search for"
     *    - "Related searches"
     *
     * 4. WEB_PAGE — page contains:
     *    - WebView element (android.webkit.WebView)
     *    - "Connection is secure" / "Connection is not secure"
     *    - "Site information" (lock icon tooltip)
     *    - "Open in new tab" / "Open in Chrome" (page context menu)
     *    - Page content with navigation elements
     *    WITHOUT new-tab or address-bar indicators
     *
     * 5. UNKNOWN — none of the above matched.
     *    Conservative: do not block.
     */
    private fun detectChromePageState(windowTextSummary: String): ChromePageState {
        val text = windowTextSummary.lowercase()

        // ── Shared signals ─────────────────────────────────────────────
        // WebView / security indicators — strongest proof of an actual loaded page.
        // These work in normal Chrome. Incognito may not expose them.
        val hasWebView = text.contains("web view")
        val hasConnectionSecure = text.contains("connection is secure")
        val hasConnectionNotSecure = text.contains("connection is not secure")
        val hasSiteInfo = text.contains("site information")
        val hasPageNav = text.contains("open in new tab") ||
            text.contains("open in chrome") ||
            text.contains("open in other app")
        val hasWebViewOrSecurity = hasWebView || hasConnectionSecure ||
            hasConnectionNotSecure || hasSiteInfo

        // ── NEW_TAB detection (normal Chrome) ──────────────────────────
        val hasSearchPlaceholder = text.contains("search google or type url") ||
            text.contains("search or type url") ||
            text.contains("type a url or search")
        // "shortcuts" must NOT appear with three-dot menu (menu is on every page)
        val hasShortcutsHeading = text.contains("shortcuts") && !text.contains("customise and control") &&
            !text.contains("customize and control") && !hasWebViewOrSecurity
        val hasNewTabText = text.contains("new tab") || text.contains("tab groups")

        if (hasSearchPlaceholder) {
            return ChromePageState.NEW_TAB
        }
        if (hasShortcutsHeading && !hasWebViewOrSecurity) {
            return ChromePageState.NEW_TAB
        }
        if (hasNewTabText && !hasWebViewOrSecurity) {
            return ChromePageState.NEW_TAB
        }

        // ── Incognito NEW_TAB detection ────────────────────────────────
        // These strings ONLY appear on the Incognito new-tab page, NOT on loaded pages.
        val hasIncognitoNewTab = text.contains("you've gone incognito") ||
            text.contains("you've gone incognito") ||
            text.contains("turn off incognito") ||
            text.contains("pages you view in this incognito")
        if (hasIncognitoNewTab) {
            return ChromePageState.NEW_TAB
        }

        // ── ADDRESS_BAR detection ──────────────────────────────────────
        val hasRecentlyVisited = text.contains("recently visited")
        val hasTrendingSearches = text.contains("trending searches")
        val hasSeeTabGroups = text.contains("see tab groups")
        val hasTurnOnIncognito = text.contains("turn on incognito")
        val hasOpenNewTab = text.contains("open new tab") && !text.contains("open in new tab")

        if (hasRecentlyVisited || hasTrendingSearches || hasSeeTabGroups ||
            hasTurnOnIncognito || (hasOpenNewTab && !hasWebViewOrSecurity)) {
            return ChromePageState.ADDRESS_BAR
        }

        // ── SEARCH_RESULTS detection ───────────────────────────────────
        val hasSearchResults = text.contains("search results") ||
            text.contains("people also search for") ||
            text.contains("related searches") ||
            text.contains("results for")

        if (hasSearchResults && !hasWebViewOrSecurity) {
            return ChromePageState.SEARCH_RESULTS
        }

        // ── WEB_PAGE detection (normal Chrome) ─────────────────────────
        if (hasWebView || hasConnectionSecure || hasConnectionNotSecure ||
            hasSiteInfo || hasPageNav) {
            if (!hasSearchPlaceholder && !hasRecentlyVisited) {
                return ChromePageState.WEB_PAGE
            }
        }

        // ── Incognito WEB_PAGE fallback ────────────────────────────────
        // Chrome Incognito may not expose WebView/security signals in the
        // accessibility window summary. We use a strict heuristic:
        // only treat as INCOGNITO_WEB_PAGE if there is evidence of actual
        // page content (not just shortcuts/new-tab UI).
        //
        // Evidence of a loaded page:
        // - Domain appears with a URL path (e.g. /posts/, /reel/, /watch/)
        // - Page-specific content present (e.g. "like", "comment", "share")
        // - The three-dot menu is present AND no new-tab-specific content
        //
        // The new-tab page has: shortcuts grid, Incognito text, search bar
        // A loaded page has: URL path, page content, three-dot menu
        val hasThreeDotMenu = text.contains("customise and control google chrome") ||
            text.contains("customize and control google chrome")
        val hasUrlPath = text.contains("/posts/") || text.contains("/reel/") ||
            text.contains("/watch/") || text.contains("/stories/") ||
            text.contains("/photos/") || text.contains("/videos/") ||
            text.contains("/page/") || text.contains("/profile/")
        val hasPageContent = text.contains("like") || text.contains("comment") ||
            text.contains("share") || text.contains("follow") ||
            text.contains("message") || text.contains("photo")
        val hasNewTabContent = hasSearchPlaceholder || hasShortcutsHeading ||
            hasNewTabText || hasIncognitoNewTab || hasRecentlyVisited ||
            hasTrendingSearches

        if (hasThreeDotMenu && !hasNewTabContent && (hasUrlPath || hasPageContent)) {
            return ChromePageState.INCOGNITO_WEB_PAGE
        }

        // ── Fallback: UNKNOWN ──────────────────────────────────────────
        // Cannot determine state — conservative: do not block
        return ChromePageState.UNKNOWN
    }

    /**
     * Determine whether a URL candidate is the active page domain
     * versus something merely visible in Chrome's UI (shortcut, suggestion, etc.).
     *
     * Uses [detectChromePageState] to classify Chrome's state, then:
     * - WEB_PAGE → candidate is the active page domain → return true
     * - NEW_TAB / ADDRESS_BAR / SEARCH_RESULTS / UNKNOWN → return false
     *
     * Additionally checks that the domain appears in the window summary
     * (not just in address bar text or event text, which could be suggestions).
     */
    private fun isActivePageDomain(
        candidate: String,
        eventText: String,
        sourceNodeText: String,
        windowTextSummary: String,
    ): Boolean {
        val normalized = DomainPolicy.normalize(candidate) ?: return false

        // First, detect Chrome's state
        val state = detectChromePageState(windowTextSummary)

        // Only WEB_PAGE and INCOGNITO_WEB_PAGE allow blocking
        if (state != ChromePageState.WEB_PAGE && state != ChromePageState.INCOGNITO_WEB_PAGE) {
            return false
        }

        // State is WEB_PAGE — verify the domain actually appears in the window summary
        // (not just in address bar text, which could be autocomplete)
        val windowLower = windowTextSummary.lowercase()
        if (!windowLower.contains(normalized)) {
            return false
        }

        return true
    }

    /**
     * Handle a detected URL candidate from Chrome for potential blocking.
     *
     * Flow:
     * 1. Check if blocking is enabled (non-empty blocked domains)
     * 2. Extract domain from the candidate
     * 3. Check if it matches any blocked domain
     * 4. Detect Chrome's page state — only block on WEB_PAGE
     * 5. Verify the domain appears in the page content
     * 6. Apply debounce (same domain ignored for [WEB_BLOCK_DEBOUNCE_MS])
     * 7. If all checks pass, launch BlockActivity
     */
    private fun handleWebBlockCandidate(
        candidate: String,
        eventText: String,
        sourceNodeText: String,
        windowTextSummary: String,
    ) {
        if (blockedDomains.isEmpty()) return

        val matchedDomain = matchBlockedDomain(candidate) ?: return

        // Check if this is the active page (not shortcuts, suggestions, address bar, etc.)
        if (!isActivePageDomain(candidate, eventText, sourceNodeText, windowTextSummary)) {
            return
        }

        // Debounce: same blocked domain ignored for WEB_BLOCK_DEBOUNCE_MS
        val now = System.currentTimeMillis()
        val lastBlock = webBlockDomainTimestamps[matchedDomain] ?: 0L
        if (now - lastBlock < WEB_BLOCK_DEBOUNCE_MS) {
            return
        }

        webBlockDomainTimestamps[matchedDomain] = now

        if (matchedDomain != lastBlockedWebDomain) {
            Log.i(WEB_BLOCK_TAG, "BLOCK: $matchedDomain")
            lastBlockedWebDomain = matchedDomain
        }

        // Interrupt Chrome navigation via back action
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(WEB_BLOCK_TAG, "GLOBAL_ACTION_BACK failed: ${e.message}")
        }

        // Show blocking screen
        try {
            val reason = "Website blocked: $matchedDomain"
            BlockActivity.launch(applicationContext, PACKAGE_CHROME, reason)
        } catch (e: Exception) {
            Log.e(WEB_BLOCK_TAG, "Failed to launch BlockActivity for web block: ${e.message}")
        }
    }
}
