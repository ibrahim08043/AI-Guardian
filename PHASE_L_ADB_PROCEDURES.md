# PHASE L — DEVICE OWNER POC PHYSICAL TEST
## ADB Commands & Procedures

**Status**: APK build in progress (Gradle assembleDebug)

---

## PART 1: DEVICE PREPARATION

### Step 1.1: Pre-Provisioning Checks

Before provisioning, verify device state:

```bash
# Check if any device admin/owner is already set
adb shell dumpsys device_policy

# Look for:
# - "Device Owner:" section
# - "Admin Users:" section
# - Any existing admins
```

**Expected output**: If device is fresh, these sections should be empty or show no admins.

**If Device Owner already exists**:
```bash
# Remove existing Device Owner (if not system-critical)
adb shell dpm remove-active-admin com.example.existing/.admin.Receiver
```

### Step 1.2: Check for User Accounts

Device Owner provisioning may be blocked if:
- Device has multiple user accounts
- Device has restricted profiles
- Device has work profiles

```bash
# Check user accounts
adb shell pm list users

# Expected: Primary user only
```

---

## PART 2: INSTALL APK

Once build completes:

```bash
# Locate built APK
ls -la build/app/outputs/flutter-apk/app-debug.apk

# Install on connected device
adb install build/app/outputs/flutter-apk/app-debug.apk

# Expected: "Success"
```

---

## PART 3: PROVISION DEVICE OWNER

### Step 3.1: Provision AI Guardian as Device Owner

```bash
adb shell dpm set-device-owner com.aiguardian.ai_guardian/.admin.DeviceOwnerReceiver
```

**Possible outcomes**:

**SUCCESS**:
```
Success: Device owner set to package com.aiguardian.ai_guardian, 
  admin type: DeviceAdminReceiver, 
  package: com.aiguardian.ai_guardian.admin.DeviceOwnerReceiver
```

**FAILURE (most common)**:
```
Error: Device owner cannot be set on a device with user restrictions
or
Error: Device owner cannot be set on a device with an existing Device Owner
or
Error: Cannot set Device Owner because there is an existing Device Owner
or
Error: Cannot set Device owner. Multiple accounts found
```

### Step 3.2: If Provisioning Fails

**Option A**: Remove existing admin
```bash
adb shell dpm remove-active-admin com.aiguardian.ai_guardian/.admin.DeviceOwnerReceiver
```

**Option B**: Factory reset (if acceptable)
```bash
adb shell am start -a android.intent.action.FACTORY_RESET
# or via Settings → About → Factory reset
```

**Option C**: Check and clear user restrictions
```bash
adb shell dumpsys device_policy | grep -A 20 "User Restrictions"
```

### Step 3.3: Verify Device Owner Status

```bash
adb shell dumpsys device_policy | grep "Device Owner"

# Expected output:
# Device Owner: 1
# Device Owner Name: com.aiguardian.ai_guardian
# Device Owner Admin:
#   DeviceAdminReceiver
#   (is_owner=true)
```

---

## PART 4: TEST CHROME POLICY APPLICATION

### Step 4.1: Open AI Guardian App

```bash
adb shell am start -n com.aiguardian.ai_guardian/.MainActivity
```

### Step 4.2: Call getDeviceOwnerStatus (via Flutter app)

In the Settings/Platform Status section:
- Look for "Device Owner: Enabled"
- Look for "Chrome Policy: Active"

**Or via ADB**:
```bash
# Check if Chrome policy was applied
adb shell dumpsys device_policy | grep -A 30 "Managed configurations"
```

### Step 4.3: Apply YouTube Blocklist

In the app, call `applyChromeBlocklist(['youtube.com'])`:
- Open Settings
- Navigate to Websites section (Phase K feature)
- Add `youtube.com` to blocked list
- Or call platform method directly if UI exists

**Expected response**: `{ applied: true, domainCount: 1 }`

### Step 4.4: Verify Chrome Policy Applied

```bash
adb shell dumpsys device_policy | grep -A 50 "Application restrictions"

# Look for:
# com.android.chrome:
#   URLBlocklist: [youtube.com]
```

---

## PART 5: CRITICAL PHYSICAL TEST — CHROME BEHAVIOR

This is where the POC succeeds or fails.

### Step 5.1: Open Chrome (Method A: Via ADB)

```bash
adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
```

### Step 5.2: Navigate to youtube.com

```bash
adb shell am start -a android.intent.action.VIEW -d "https://youtube.com"
```

**What to look for on device screen**:

**SUCCESS (URL BLOCKED)**:
- Chrome shows error page: "ERR_BLOCKED_BY_ADMINISTRATOR" or similar
- User sees "This website is not available" or policy block message
- YouTube page does NOT load
- URL bar shows `youtube.com` but page is blocked

**FAILURE (NOT BLOCKED)**:
- YouTube homepage loads normally
- User can watch videos
- No error message
- Policy had no effect

**CAPTURE EVIDENCE**:
```bash
adb shell screencap -p /sdcard/youtube_blocked_test.png
adb pull /sdcard/youtube_blocked_test.png ~/youtube_blocked.png
```

### Step 5.3: Test google.com (should load)

```bash
adb shell am start -a android.intent.action.VIEW -d "https://google.com"
```

**Expected**: Google homepage loads normally, no blocking

```bash
adb shell screencap -p /sdcard/google_allowed_test.png
adb pull /sdcard/google_allowed_test.png ~/google_allowed.png
```

### Step 5.4: Test Other Domains

```bash
# Test reddit.com (should load)
adb shell am start -a android.intent.action.VIEW -d "https://reddit.com"

# Test www.youtube.com (should match youtube.com block)
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com"

# Test m.youtube.com (should also be blocked if policy works properly)
adb shell am start -a android.intent.action.VIEW -d "https://m.youtube.com"
```

### Step 5.5: Test Chrome Incognito Mode

```bash
# Open Incognito tab
adb shell am start -n com.android.chrome/com.android.chrome.InnerActivity \
  -e "send_to_external_handler" "false" \
  -e "incognito" "true"

# Navigate to youtube.com
adb shell am start -a android.intent.action.VIEW -d "https://youtube.com"
```

**Expected if policy works**:
- Even in Incognito, youtube.com is blocked
- Same error page as normal mode

**Expected if policy doesn't work**:
- Incognito may bypass the policy
- YouTube loads normally in Incognito

---

## PART 6: VERIFY INTERNET NOT BROKEN

Unlike VPN approach, normal internet should work:

```bash
# Test Wi-Fi connectivity
adb shell ping -c 4 8.8.8.8
# Expected: 4 packets received, 0% loss

# Test DNS resolution
adb shell getprop net.change
adb shell getprop net.dns1

# Open other apps
adb shell am start -n com.google.android.gms/.maps.MapsActivity
# Gmail, Maps, etc. should have internet access

# Test streaming
adb shell am start -n "com.spotify.music/.MainActivity"
```

**Expected**: All internet-dependent apps work normally, no connection breaks

---

## PART 7: PHASE C REGRESSION TEST

Verify existing functionality still works:

```bash
# Open an Always Block app
adb shell am start -n com.instagram.android/.MainActivity

# Expected: BlockActivity shows instead of Instagram
```

Check in app:
- Always Block restrictions active
- Scheduled Blocking rules intact
- Daily Usage Limits working

---

## PART 8: CLEANUP

If test fails and you want to re-test:

### Remove Device Owner

```bash
adb shell dpm remove-active-admin com.aiguardian.ai_guardian/.admin.DeviceOwnerReceiver
```

### Verify removed

```bash
adb shell dumpsys device_policy | grep "Device Owner"
# Should show: Device Owner: 0
```

### Clear Chrome policy

```bash
adb shell cmd appops reset com.android.chrome
```

---

## LOGCAT MONITORING

Keep logcat running during tests to capture logs:

```bash
adb logcat -s "*AIGuardian*" -v threadtime > /tmp/aiguardian.log &
adb logcat -s "*Chrome*" -v threadtime > /tmp/chrome.log &
```

Look for:
```
[DEVICE_OWNER] isDeviceOwner: true
[CHROME_POLICY] Applying URLBlocklist
[CHROME_POLICY] Apply result: true
```

---

## SUCCESS CRITERIA

**POC SUCCEEDS** if and ONLY if:
1. ✅ Device Owner provisioned: `dpm` shows Device Owner = AI Guardian
2. ✅ Chrome policy applied: `dumpsys` shows URLBlocklist restriction
3. ✅ YouTube BLOCKED in Chrome: User navigates to youtube.com, sees blocked page
4. ✅ Google ALLOWED: google.com loads normally
5. ✅ YouTube BLOCKED in Incognito: Policy persists in private mode
6. ✅ Internet not broken: Other apps have internet, no VPN used
7. ✅ Phase C works: Existing restrictions still enforce

**POC FAILS** if any of these is false:
- Device Owner provisioning fails (device restriction prevents it)
- Chrome ignores the URLBlocklist policy (policy applied but has no effect)
- youtube.com loads normally (Chrome doesn't honor policy)
- Internet breaks (similar to VPN issue)

---

## EXPECTED TIMELINE

- Build APK: 5-10 minutes
- Provision Device Owner: < 1 minute
- Apply Chrome policy: < 1 minute
- Test Chrome behavior: 2-3 minutes per test case
- **Total**: ~15-20 minutes

**Will report results after physical testing on SM-A057F.**

