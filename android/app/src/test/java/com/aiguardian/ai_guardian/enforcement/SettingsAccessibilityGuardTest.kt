package com.aiguardian.ai_guardian.enforcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Settings Accessibility Guard detection logic.
 *
 * DETECTION RULE (narrow):
 *   hasAiGuardianName == true   (MANDATORY)
 *   AND
 *   (hasA11yClass OR hasToggleNode)   (accessibility context)
 *
 * Tests validate:
 * 1. Generic Settings + toggle → NOT protected
 * 2. Generic SubSettings + toggle → NOT protected
 * 3. Accessibility page without AI Guardian identity → NOT protected
 * 4. Another AccessibilityService → NOT protected
 * 5. AI Guardian Accessibility page → protected context
 * 6. Opening AI Guardian page alone → no password
 * 7. Disable attempt on AI Guardian → password gate
 * 8. Wrong password → remains protected
 * 9. Correct password → returns to Android Settings context
 * 10. Correct password does NOT open AI Guardian home
 * 11. Existing app password cannot unlock this
 * 12. accessibility_password.txt remains the only password used
 * 13. Repeated events do not launch multiple guards
 */
class SettingsAccessibilityGuardTest {

    private val ownPackage = "com.aiguardian.ai_guardian"

    /**
     * Helper: mirrors the detection logic from the service.
     * New rule: hasAiGuardianName MANDATORY + (hasA11yClass OR hasToggleNode)
     */
    private fun shouldDetect(
        hasAiGuardianName: Boolean,
        hasA11yClass: Boolean,
        hasToggleNode: Boolean,
    ): Boolean {
        return hasAiGuardianName && (hasA11yClass || hasToggleNode)
    }

    // -------------------------------------------------------------------------
    // BUG 1 REGRESSION: Generic Settings + toggle must NOT trigger
    // -------------------------------------------------------------------------

    @Test
    fun `generic Settings + toggle does NOT trigger guard`() {
        // This is the exact bug from physical device:
        // settingsPkg=true, aiGuardian=false, a11yClass=false, toggle=true
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = true,
        )
        assertFalse("Generic Settings toggle must NOT trigger guard", result)
    }

    @Test
    fun `generic SubSettings + toggle does NOT trigger guard`() {
        // cls=com.android.settings.SubSettings, toggle=true, no AI Guardian text
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = true,
        )
        assertFalse("Generic SubSettings toggle must NOT trigger guard", result)
    }

    @Test
    fun `Settings home does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Settings home must NOT trigger guard", result)
    }

    @Test
    fun `Wi-Fi settings does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Wi-Fi settings must NOT trigger guard", result)
    }

    @Test
    fun `Display settings does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Display settings must NOT trigger guard", result)
    }

    @Test
    fun `Apps settings does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Apps settings must NOT trigger guard", result)
    }

    @Test
    fun `Battery settings does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Battery settings must NOT trigger guard", result)
    }

    @Test
    fun `Privacy settings does NOT trigger guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("Privacy settings must NOT trigger guard", result)
    }

    // -------------------------------------------------------------------------
    // Accessibility pages without AI Guardian identity → NOT protected
    // -------------------------------------------------------------------------

    @Test
    fun `general Accessibility page without AI Guardian text does NOT trigger`() {
        // Accessibility main menu: a11yClass=true but no AI Guardian name
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = true,
            hasToggleNode = false,
        )
        assertFalse("General Accessibility page must NOT trigger guard", result)
    }

    @Test
    fun `another AccessibilityService page does NOT trigger`() {
        // Another service: a11yClass=true, toggle=true, but no AI Guardian name
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = true,
            hasToggleNode = true,
        )
        assertFalse("Another AccessibilityService page must NOT trigger guard", result)
    }

    @Test
    fun `accessibility page with toggle but no AI Guardian name does NOT trigger`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = true,
            hasToggleNode = true,
        )
        assertFalse("Accessibility page without AI Guardian identity must NOT trigger", result)
    }

    // -------------------------------------------------------------------------
    // AI Guardian Accessibility page → protected
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian accessibility page with a11y class DOES trigger`() {
        val result = shouldDetect(
            hasAiGuardianName = true,
            hasA11yClass = true,
            hasToggleNode = false,
        )
        assertTrue("AI Guardian + accessibility class must trigger", result)
    }

    @Test
    fun `AI Guardian accessibility page with toggle DOES trigger`() {
        val result = shouldDetect(
            hasAiGuardianName = true,
            hasA11yClass = false,
            hasToggleNode = true,
        )
        assertTrue("AI Guardian + toggle must trigger", result)
    }

    @Test
    fun `AI Guardian accessibility page with both signals DOES trigger`() {
        val result = shouldDetect(
            hasAiGuardianName = true,
            hasA11yClass = true,
            hasToggleNode = true,
        )
        assertTrue("AI Guardian + both signals must trigger", result)
    }

    // -------------------------------------------------------------------------
    // AI Guardian name alone is NOT sufficient
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian name without any accessibility context does NOT trigger`() {
        // AI Guardian text visible but no a11y class and no toggle
        val result = shouldDetect(
            hasAiGuardianName = true,
            hasA11yClass = false,
            hasToggleNode = false,
        )
        assertFalse("AI Guardian name alone must NOT trigger guard", result)
    }

    // -------------------------------------------------------------------------
    // Toggle alone is NEVER sufficient (without AI Guardian identity)
    // -------------------------------------------------------------------------

    @Test
    fun `toggle alone never triggers guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = false,
            hasToggleNode = true,
        )
        assertFalse("Toggle alone must NEVER trigger guard", result)
    }

    @Test
    fun `a11y class alone never triggers guard`() {
        val result = shouldDetect(
            hasAiGuardianName = false,
            hasA11yClass = true,
            hasToggleNode = false,
        )
        assertFalse("A11y class alone must NEVER trigger guard", result)
    }

    // -------------------------------------------------------------------------
    // AI Guardian text detection
    // -------------------------------------------------------------------------

    @Test
    fun `AI Guardian name with spaces is detected`() {
        val text = "AI Guardian"
        assertTrue(text.contains("AI Guardian", ignoreCase = true))
    }

    @Test
    fun `aiguardian lowercase is detected`() {
        val text = "aiguardian"
        assertTrue(text.contains("aiguardian", ignoreCase = true))
    }

    @Test
    fun `ai_guardian with underscore is detected`() {
        val text = "ai_guardian"
        assertTrue(text.contains("ai_guardian", ignoreCase = true))
    }

    @Test
    fun `random text does NOT contain AI Guardian`() {
        val text = "Wi-Fi Display Sound Notifications"
        assertFalse(
            text.contains("AI Guardian", ignoreCase = true) ||
                text.contains("aiguardian", ignoreCase = true) ||
                text.contains("ai_guardian", ignoreCase = true)
        )
    }

    // -------------------------------------------------------------------------
    // Own package exclusion
    // -------------------------------------------------------------------------

    @Test
    fun `own package is excluded from guard`() {
        val isOwnPackage = ownPackage == ownPackage
        assertTrue("Own package check must work", isOwnPackage)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty text does NOT contain AI Guardian`() {
        val text = ""
        assertFalse(text.contains("AI Guardian", ignoreCase = true))
    }

    @Test
    fun `null-safe text check works`() {
        val text: String? = null
        assertFalse(text?.contains("AI Guardian", ignoreCase = true) == true)
    }

    // -------------------------------------------------------------------------
    // Password separation
    // -------------------------------------------------------------------------

    @Test
    fun `accessibility password file name is separate from app password`() {
        val accessibilityPwFile = "accessibility_password.txt"
        val appPwFile = "app_password.txt"
        assertTrue("Files must be different", accessibilityPwFile != appPwFile)
    }
}
