package com.aiguardian.ai_guardian.enforcement

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.aiguardian.ai_guardian.R
import com.aiguardian.ai_guardian.service.AIGuardianAccessibilityService

/**
 * Settings Accessibility Guard — password protection for AI Guardian's
 * AccessibilityService disable flow in Android Settings.
 *
 * Launched by the AccessibilityService when the user navigates to
 * AI Guardian's AccessibilityService details page in Settings and
 * appears to be attempting to disable it.
 *
 * This Activity presents a password screen that must be completed
 * before the user can proceed with the disable action.
 *
 * CRITICAL: This reads from assets/accessibility_password.txt — a
 * COMPLETELY SEPARATE credential from assets/app_password.txt.
 * The two passwords must never be cross-referenced or substituted.
 *
 * This is a BEST-EFFORT AccessibilityService protection layer, NOT Device
 * Owner protection. The protection depends on the AccessibilityService
 * remaining enabled.
 *
 * RETURN BEHAVIOR:
 * - Correct password → finish() → underlying Settings task resumes
 * - Wrong password → remain on screen with error
 * - Back/Cancel → finish() → underlying Settings task resumes
 *
 * If the Settings task was destroyed, falls back to opening Accessibility Settings.
 */
class SettingsAccessibilityGuardActivity : Activity() {

    companion object {
        private const val TAG = "AIGuardianSettingsGuard"

        fun launch(context: Context) {
            val intent = Intent(context, SettingsAccessibilityGuardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            try {
                context.startActivity(intent)
                Log.i(TAG, "SettingsAccessibilityGuardActivity launched")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch SettingsAccessibilityGuardActivity: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_accessibility_guard)

        Log.i(TAG, "SettingsAccessibilityGuardActivity created")

        val passwordInput = findViewById<EditText>(R.id.settings_guard_password_input)
        val errorMessage = findViewById<TextView>(R.id.settings_guard_error_message)
        val verifyButton = findViewById<Button>(R.id.settings_guard_verify_button)
        val cancelButton = findViewById<Button>(R.id.settings_guard_cancel_button)

        // Focus the password input and show keyboard
        passwordInput.requestFocus()
        passwordInput.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        verifyButton.setOnClickListener {
            val enteredPassword = passwordInput.text.toString()

            if (enteredPassword.isEmpty()) {
                errorMessage.text = "Please enter the password"
                errorMessage.visibility = TextView.VISIBLE
                return@setOnClickListener
            }

            val isCorrect = verifyPassword(enteredPassword)

            if (isCorrect) {
                Log.i(TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: PASSWORD_SUCCESS")
                // Password correct — dismiss guard, return to Settings
                returnToSettings()
            } else {
                Log.i(TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: PASSWORD_FAILED")
                errorMessage.text = "Incorrect password"
                errorMessage.visibility = TextView.VISIBLE
                passwordInput.text.clear()
            }
        }

        cancelButton.setOnClickListener {
            Log.i(TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: PASSWORD_CANCELLED")
            returnToSettings()
        }
    }

    /**
     * Verify the entered password against the ACCESSIBILITY password in assets.
     *
     * CRITICAL: This reads from assets/accessibility_password.txt —
     * NOT assets/app_password.txt. The two passwords are completely separate.
     */
    private fun verifyPassword(password: String): Boolean {
        return try {
            val storedPassword = assets.open("accessibility_password.txt")
                .bufferedReader()
                .use { it.readText().trimEnd('\r', '\n') }
            password == storedPassword
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read accessibility password from assets: ${e.message}")
            // FAIL-CLOSED: if we can't read the password, deny access
            false
        }
    }

    /**
     * Return to the Android Settings Accessibility screen.
     *
     * Strategy:
     * 1. Just finish() — the underlying Settings task should resume from the back stack.
     * 2. If Settings task was destroyed, fall back to opening Accessibility Settings
     *    via the standard Android Intent.
     *
     * This ensures the user returns to Settings, NOT to AI Guardian's main app.
     */
    private fun returnToSettings() {
        try {
            // Simply finish this activity. The Settings task underneath should resume.
            // We launched with NEW_TASK | NO_HISTORY, so this activity won't stay in history.
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish guard activity: ${e.message}")
            // Fallback: open Accessibility Settings directly
            openAccessibilitySettingsFallback()
        }
    }

    /**
     * Fallback: open Android Accessibility Settings if the Settings task
     * was destroyed and cannot be resumed.
     */
    private fun openAccessibilitySettingsFallback() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.i(TAG, "Opened Accessibility Settings as fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Accessibility Settings fallback: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.i(TAG, "AI_GUARDIAN_SETTINGS_ACCESSIBILITY_GUARD: PASSWORD_CANCELLED (back)")
        returnToSettings()
    }

    override fun onDestroy() {
        // Clear the guard active state so future detection can work
        try {
            AIGuardianAccessibilityService.instance?.clearSettingsAccessibilityGuard()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear settings accessibility guard: ${e.message}")
        }
        super.onDestroy()
        Log.i(TAG, "SettingsAccessibilityGuardActivity destroyed")
    }
}
