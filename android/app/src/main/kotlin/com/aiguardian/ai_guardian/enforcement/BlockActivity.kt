package com.aiguardian.ai_guardian.enforcement

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.aiguardian.ai_guardian.MainActivity
import com.aiguardian.ai_guardian.R
import com.aiguardian.ai_guardian.service.AIGuardianAccessibilityService

/**
 * Intervention screen displayed when a blocked app is detected.
 *
 * Shows a blocking screen with the app name, block reason,
 * and the fixed intervention text from voice_intervention_text.txt.
 *
 * NO voice, NO TTS, NO AI, NO microphone.
 * Enforcement path: AccessibilityService → PolicyEngine → EnforcementManager → BlockActivity
 */
class BlockActivity : Activity() {

    private var blockedPackage: String = ""
    private var blockReason: String = ""

    companion object {
        private const val TAG = "AIGuardianBlock"
        private const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
        private const val EXTRA_BLOCK_REASON = "block_reason"

        fun launch(context: Context, packageName: String, reason: String = "") {
            val intent = Intent(context, BlockActivity::class.java).apply {
                putExtra(EXTRA_BLOCKED_PACKAGE, packageName)
                putExtra(EXTRA_BLOCK_REASON, reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(intent)
                Log.d(TAG, "BlockActivity launched for $packageName (reason: $reason)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockActivity: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)

        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "Unknown"
        blockReason = intent.getStringExtra(EXTRA_BLOCK_REASON) ?: ""
        Log.d(TAG, "BlockActivity created for $blockedPackage (reason: $blockReason)")

        val titleView = findViewById<TextView>(R.id.block_title)
        val messageView = findViewById<TextView>(R.id.block_message)
        val packageView = findViewById<TextView>(R.id.block_package)
        val reasonView = findViewById<TextView>(R.id.block_reason)
        val interventionTextView = findViewById<TextView>(R.id.intervention_text)
        val backButton = findViewById<Button>(R.id.block_back_button)

        titleView.text = "App Blocked"

        messageView.text = when {
            blockReason.contains("schedule") -> "This app is blocked during the scheduled time window."
            blockReason.contains("daily limit") -> "You've reached your daily usage limit for this app."
            blockReason.contains("policy") -> "This app is currently blocked by AI Guardian."
            else -> "This app is currently blocked by AI Guardian."
        }

        packageView.text = blockedPackage

        if (blockReason.isNotEmpty()) {
            reasonView.text = "Reason: $blockReason"
            reasonView.visibility = android.view.View.VISIBLE
        } else {
            reasonView.visibility = android.view.View.GONE
        }

        // Load and display the fixed intervention text
        val interventionText = loadInterventionText()
        interventionTextView.text = interventionText
        interventionTextView.visibility = android.view.View.VISIBLE
        Log.d(TAG, "Intervention text displayed: \"${interventionText.take(60)}...\"")

        backButton.setOnClickListener {
            openAIGuardian()
        }
    }

    /**
     * Load the fixed intervention text from assets/voice_intervention_text.txt.
     */
    private fun loadInterventionText(): String {
        return try {
            assets.open("voice_intervention_text.txt").bufferedReader().use { reader ->
                reader.readText().trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice_intervention_text.txt: ${e.message}")
            "This app is currently blocked."
        }
    }

    override fun onDestroy() {
        // Reset enforcement state so the AccessibilityService can re-evaluate
        if (blockedPackage.isNotEmpty()) {
            try {
                AIGuardianAccessibilityService.instance
                    ?.enforcementManager
                    ?.resetForPackage(blockedPackage)
                Log.d(TAG, "Enforcement reset for $blockedPackage")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset enforcement: ${e.message}")
            }
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.d(TAG, "Back pressed — routing to AI Guardian")
        openAIGuardian()
    }

    private fun openAIGuardian() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.d(TAG, "Launched MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity: ${e.message}")
        } finally {
            finish()
        }
    }
}
