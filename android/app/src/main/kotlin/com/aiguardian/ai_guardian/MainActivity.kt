package com.aiguardian.ai_guardian

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import android.util.Log
import android.os.Bundle
import com.aiguardian.ai_guardian.platform.PlatformChannelHandler
import com.aiguardian.ai_guardian.policy.PolicyEngine
import com.aiguardian.ai_guardian.policy.UsageTracker
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper
import com.aiguardian.ai_guardian.storage.PolicyRepository
import com.aiguardian.ai_guardian.storage.DomainRepository
import com.aiguardian.ai_guardian.service.AIGuardianAccessibilityService
import com.aiguardian.ai_guardian.service.DomainBlockerVpnService
import android.net.VpnService
import android.content.Intent

/**
 * Main entry point for the Android native side.
 *
 * Hosts the Flutter engine and routes MethodChannel/EventChannel
 * communication to [PlatformChannelHandler].
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "com.aiguardian.ai_guardian/method"
        private const val EVENT_CHANNEL = "com.aiguardian.ai_guardian/event"
        private const val TAG = "AIGuardianMain"
        private const val VPN_PERMISSION_REQUEST = 1001

        @Volatile
        var eventSink: EventChannel.EventSink? = null
            private set
    }

    private lateinit var platformHandler: PlatformChannelHandler

    /**
     * Stores the Flutter MethodChannel.Result callback while we wait
     * for the user to respond to the VPN consent dialog.
     */
    private var vpnPendingResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        platformHandler = PlatformChannelHandler(applicationContext)

        // Initialize persistent storage
        val dbHelper = PolicyDatabaseHelper(applicationContext)
        val repository = PolicyRepository(dbHelper)

        // Initialize UsageTracker for session tracking
        val usageTracker = UsageTracker(repository)

        // Initialize PolicyEngine with persistent storage and usage tracking
        val policyEngine = PolicyEngine(repository)
        policyEngine.usageTracker = usageTracker

        // Inject into AccessibilityService
        AIGuardianAccessibilityService.policyEngine = policyEngine
        AIGuardianAccessibilityService.usageTracker = usageTracker

        // MethodChannel — request/response from Flutter
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                Log.i(TAG, "MethodChannel: ${call.method}")
                try {
                    when (call.method) {
                        "startDomainBlocking" -> {
                            handleStartDomainBlocking(call, result)
                        }
                        else -> {
                            if (!platformHandler.handle(call, result)) {
                                result.notImplemented()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MethodChannel crash: ${e.message}", e)
                    result.error("CRASH", e.message, null)
                }
            }

        // EventChannel — native -> Flutter streaming
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    /**
     * Handle startDomainBlocking with proper VPN permission flow.
     *
     * Flow:
     * 1. Check VpnService.prepare()
     * 2. If non-null Intent → user consent needed → launch consent UI
     * 3. If null → consent already granted → start VPN directly
     * 4. In onActivityResult → start VPN if user approved
     */
    private fun handleStartDomainBlocking(
        call: io.flutter.plugin.common.MethodCall,
        result: MethodChannel.Result,
    ) {
        Log.i(TAG, "handleStartDomainBlocking called")

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // User consent NOT yet granted — launch system VPN consent dialog
            Log.i(TAG, "VPN consent required, launching system dialog")
            vpnPendingResult = result
            // Do NOT add FLAG_ACTIVITY_NEW_TASK — that breaks onActivityResult
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
        } else {
            // Consent already granted — delegate to PlatformChannelHandler
            Log.i(TAG, "VPN consent already granted, proceeding")
            if (!platformHandler.handle(call, result)) {
                result.notImplemented()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_PERMISSION_REQUEST) {
            val pendingResult = vpnPendingResult
            vpnPendingResult = null

            if (resultCode == RESULT_OK) {
                Log.i(TAG, "VPN consent GRANTED by user")
                // Load blocked domains from DB and start VPN
                try {
                    val dbHelper = PolicyDatabaseHelper(applicationContext)
                    val domainRepo = DomainRepository(dbHelper)
                    val blockedDomains = domainRepo.getBlockedDomainSet()
                    Log.i(TAG, "Loaded ${blockedDomains.size} blocked domains")
                    DomainBlockerVpnService.updateBlockedDomains(blockedDomains)
                    DomainBlockerVpnService.start(applicationContext)
                    pendingResult?.success(mapOf("success" to true))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start VPN: ${e.message}", e)
                    pendingResult?.error("START_VPN_ERROR", e.message, null)
                }
            } else {
                Log.w(TAG, "VPN consent DENIED by user")
                pendingResult?.success(mapOf("success" to false))
            }
        }
    }
}
