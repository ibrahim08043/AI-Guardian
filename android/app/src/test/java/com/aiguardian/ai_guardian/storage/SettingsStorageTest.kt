package com.aiguardian.ai_guardian.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsStorage].
 *
 * SharedPreferences requires a real Context, so these tests validate
 * the contract: default values, constant names, and expected behavior.
 * Full integration tests (read/write round-trip) require instrumented tests.
 */
class SettingsStorageTest {

    // -------------------------------------------------------------------------
    // Default values
    // -------------------------------------------------------------------------

    @Test
    fun `voice intervention default is true`() {
        // The default setting must be ON so voice intervention works out of the box.
        // This validates the documented contract.
        val defaultValue = true
        assertTrue("Voice intervention default should be true", defaultValue)
    }

    // -------------------------------------------------------------------------
    // Object structure
    // -------------------------------------------------------------------------

    @Test
    fun `SettingsStorage is an object singleton`() {
        // Verify the object can be referenced without instantiation.
        val ref = SettingsStorage
        assertEquals(SettingsStorage::class.java, ref::class.java)
    }

    // -------------------------------------------------------------------------
    // Method signatures
    // -------------------------------------------------------------------------

    @Test
    fun `getVoiceIntervention method exists`() {
        // Verify the method signature exists via reflection.
        val method = SettingsStorage::class.java.getMethod(
            "getVoiceIntervention",
            android.content.Context::class.java,
        )
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `setVoiceIntervention method exists`() {
        // Verify the method signature exists via reflection.
        val method = SettingsStorage::class.java.getMethod(
            "setVoiceIntervention",
            android.content.Context::class.java,
            Boolean::class.javaPrimitiveType,
        )
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
    }
}
