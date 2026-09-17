package com.aiguardian.ai_guardian.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver for Device Owner capabilities.
 *
 * This receiver allows AI Guardian to be provisioned as a Device Owner,
 * enabling system-level policy management including Chrome configuration.
 *
 * Phase L: Non-VPN website blocking investigation.
 */
class DeviceOwnerReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AIGuardianDeviceOwner"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Admin enabled")
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Admin disabled")
        super.onDisabled(context, intent)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received: ${intent.action}")
        super.onReceive(context, intent)
    }
}
