# PHASE L — DEVICE OWNER POC TEST PLAN

**Status**: Minimal Device Owner implementation created. Awaiting build verification.

**Implementation Summary**:
- Created `DeviceOwnerReceiver.kt` — Device Admin receiver
- Created `DeviceOwnerManager.kt` — Manages Device Owner status and Chrome policies
- Created `device_admin_receiver.xml` — Device Admin policy declarations
- Updated AndroidManifest.xml with Device Admin receiver and MANAGE_DEVICE_ADMINS permission
- Added 4 platform channel methods to PlatformChannelHandler:
  - `getDeviceOwnerStatus()` — Check Device Owner status
  - `applyChromeBlocklist()` — Attempt to apply URLBlocklist policy
  - `getChromePolicy()` — Retrieve current Chrome policy
  - `clearChromePolicy()` — Remove Chrome policy

---

## PHASE L TEST PROCEDURE

### Prerequisites
- Samsung Galaxy A05s (SM-A057F) with Android 15
- ADB access available
- Device not yet provisioned as Device Owner
- App will be built and installed

### Step 1: Build and Install
```bash
flutter build apk
adb install build/app/outputs/flutter-apk/app-debug.apk
```

### Step 2: Provision Device Owner via ADB

**Command to provision AI Guardian as Device Owner**:
```bash
adb shell dpm set-device-owner com.aiguardian.ai_guardian/.admin.DeviceOwnerReceiver
```

**Expected output**: 
```
Success: Device owner set to package ...
```

**If provisioning fails**, the device may:
- Already have another device admin
- Have user accounts configured
- Have other restrictions

**In that case**, report the exact error and follow the reset procedure (if acceptable).

### Step 3: Verify Device Owner Status
After provisioning, test via Flutter app:
- Open Settings/Platform Status section
- Call `getDeviceOwnerStatus()` platform method
- Verify response shows `isDeviceOwner: true`

### Step 4: Test Chrome Policy Application

**Test Case 1**: Apply youtube.com blocklist
```
Call: applyChromeBlocklist(['youtube.com'])
Expected: { applied: true, domainCount: 1 }
```

**Test Case 2**: Retrieve Chrome policy
```
Call: getChromePolicy()
Expected: { URLBlocklist: ['youtube.com'] } or similar
```

**Test Case 3**: Verify Chrome behavior (CRITICAL)
- Open Chrome on device
- Navigate to `https://youtube.com`
- **Expected**: If Chrome accepts the policy:
  - YouTube should be blocked or show error
  - User cannot access the site
  - **Actual Result**: [TO BE FILLED BY PHYSICAL TEST]
- Navigate to `https://google.com`
- **Expected**: Should load normally
  - **Actual Result**: [TO BE FILLED BY PHYSICAL TEST]

**Test Case 4**: Chrome Incognito behavior
- Open Chrome Incognito tab
- Navigate to `https://youtube.com`
- **Expected**: If policy applies to Incognito:
  - Should be blocked
  - **Actual Result**: [TO BE FILLED BY PHYSICAL TEST]
- Navigate to `https://google.com`
- **Expected**: Should load normally
  - **Actual Result**: [TO BE FILLED BY PHYSICAL TEST]

### Step 5: Verify No Internet Breakage

Unlike the VPN approach:
- Normal internet should work
- Wi-Fi/mobile should be unaffected
- DNS should resolve normally
- Other apps' internet should not be blocked

**Test**:
- Open other apps (Gmail, Maps, etc.)
- Verify they can access internet normally
- **Result**: [TO BE FILLED]

### Step 6: Phase C Regression Testing

Verify existing restrictions still work:
- Always Block enforcement
- Scheduled Blocking
- Daily Usage Limits

**Test**: Open a Phase C blocked app, verify BlockActivity shows

### Step 7: Clear Policy and De-provision

```bash
# Clear Chrome policy via app
Call: clearChromePolicy()

# De-provision Device Owner (if needed for cleanup)
adb shell dpm remove-active-admin com.aiguardian.ai_guardian/.admin.DeviceOwnerReceiver
```

---

## CRITICAL: ACTUAL VERIFICATION REQUIREMENT

**This test MUST verify actual Chrome behavior, not internal state:**

❌ **DO NOT mark as success if**:
- `applyChromeBlocklist()` returns true
- `getChromePolicy()` shows the domains
- No exceptions thrown
- Internal function logic works

✅ **Mark as success ONLY if**:
- Chrome actually blocks youtube.com (user sees blocked page or error)
- Google.com still loads normally
- No VPN required
- Internet not broken

---

## BUILD STATUS

Build started at 2026-09-14 16:29:04 UTC
Gradle compiling Kotlin sources...
(Will update when complete)

