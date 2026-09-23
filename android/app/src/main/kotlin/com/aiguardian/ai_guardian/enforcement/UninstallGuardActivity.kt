package com.aiguardian.ai_guardian.enforcement

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.aiguardian.ai_guardian.MainActivity
import com.aiguardian.ai_guardian.R
import com.aiguardian.ai_guardian.service.AIGuardianAccessibilityService

/**
 * Uninstall Guard — password protection gate for AI Guardian uninstall attempts.
 *
 * Launched by the AccessibilityService when an uninstall confirmation dialog
 * for AI Guardian is detected in the Package Installer. This Activity presents
 * a password screen that must be completed before the user can proceed.
 *
 * This is a BEST-EFFORT AccessibilityService protection layer, NOT Device Owner
 * protection. The protection depends on the AccessibilityService remaining enabled.
 * System UI changes, service disablement, Safe Mode, ADB, or other system-level
 * paths may bypass it. Device Owner/profile-owner management remains the supported
 * Android mechanism for true uninstall blocking.
 *
 * Password verification reuses the same asset file (assets/app_password.txt)
 * that the Flutter PasswordService reads. This is NOT a second password system —
 * it is the same password, same asset, same verification logic, implemented as
 * a native Android Activity because the AccessibilityService context cannot
 * directly render Flutter widgets.
 *
 * FAIL-CLOSED behavior:
 * - Correct password → dismiss protection screen, return to AI Guardian
 * - Wrong password → remain on screen with error
 * - Back/Cancel → return to AI Guardian, uninstall remains interrupted
 */
class UninstallGuardActivity : Activity() {

    companion object {
        private const val TAG = "AIGuardianUninstallGuard"
        fun launch(context: Context) {
            val intent = Intent(context, UninstallGuardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            try {
                context.startActivity(intent)
                Log.i(TAG, "UninstallGuardActivity launched")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch UninstallGuardActivity: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_guard)

        Log.i(TAG, "UninstallGuardActivity created")

        val passwordInput = findViewById<EditText>(R.id.guard_password_input)
        val errorMessage = findViewById<TextView>(R.id.guard_error_message)
        val verifyButton = findViewById<Button>(R.id.guard_verify_button)
        val cancelButton = findViewById<Button>(R.id.guard_cancel_button)

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
                Log.i(TAG, "AI_GUARDIAN_UNINSTALL_GUARD: PASSWORD_SUCCESS")
                // Password correct — dismiss protection, return to AI Guardian
                openAIGuardian()
            } else {
                Log.i(TAG, "AI_GUARDIAN_UNINSTALL_GUARD: PASSWORD_FAILED")
                errorMessage.text = "Incorrect password. Access denied."
                errorMessage.visibility = TextView.VISIBLE
                passwordInput.text.clear()
            }
        }

        cancelButton.setOnClickListener {
            Log.i(TAG, "AI_GUARDIAN_UNINSTALL_GUARD: PASSWORD_CANCELLED")
            openAIGuardian()
        }
    }

    /**
     * Verify the entered password against the stored password in assets.
     * Reuses the same asset file (app_password.txt) as the Flutter PasswordService.
     */
    private fun verifyPassword(password: String): Boolean {
        return try {
            val storedPassword = assets.open("app_password.txt")
                .bufferedReader()
                .use { it.readText().trimEnd('\r', '\n') }
            password == storedPassword
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read password from assets: ${e.message}")
            // FAIL-CLOSED: if we can't read the password, deny access
            false
        }
    }

    /**
     * Return to AI Guardian's main activity and dismiss this protection screen.
     */
    private fun openAIGuardian() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity: ${e.message}")
        } finally {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Log.i(TAG, "AI_GUARDIAN_UNINSTALL_GUARD: PASSWORD_CANCELLED (back)")
        openAIGuardian()
    }

    override fun onDestroy() {
        // Clear the guard active state so future uninstall attempts can be detected
        try {
            AIGuardianAccessibilityService.instance?.clearUninstallGuard()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear uninstall guard: ${e.message}")
        }
        super.onDestroy()
        Log.i(TAG, "UninstallGuardActivity destroyed")
    }
}
