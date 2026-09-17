# PHASE L — DEVICE OWNER + CHROME URLBLOCKLIST POC
## Research & Investigation Phase

**Objective**: Determine if AI Guardian can use Android Device Owner + Chrome managed policies to block websites WITHOUT VPN.

**Target Device**: Samsung Galaxy A05s (SM-A057F), Android 15, API 35

**Start Date**: 2026-09-14

---

## PART 1: ANDROID DEVICE OWNER API CAPABILITIES

### What is Device Owner?

Device Owner is an advanced form of device administration (DeviceAdminReceiver extension) that allows an app to manage the entire device at a system level. Key points:

- **Single per device**: Only ONE app can be Device Owner at a time.
- **System-level control**: Has elevated permissions beyond regular admin apps.
- **Provisioning**: Must be provisioned through specific mechanisms (adb, NFC, QR, MDM enrollment).
- **Policy enforcement**: Can enforce policies on the device and managed apps.

### DevicePolicyManager Key APIs for Device Owner

```
DevicePolicyManager (android.app.admin)
├── isDeviceOwnerApp(packageName)           → Check if app is Device Owner
├── getDeviceOwnerComponentName()           → Get current DO component
├── setApplicationRestrictions()            → Set app-specific restrictions
├── getApplicationRestrictions()            → Retrieve restrictions
├── setDeviceOwnerType(type, packageName)   → Set DO type (API 29+)
├── createAndManageUser()                   → User management
└── createUser(), removeUser()              → Profile/user lifecycle
```

**Status on API 35 (Android 15)**: All Device Owner APIs are available and functional.

---

## PART 2: CHROME MANAGED CONFIGURATION / RESTRICTIONS

### Chrome on Android + Device Owner

Chrome on Android **does support** managed configurations (app restrictions) when the device has a Device Owner provisioned. However, the specific mechanism matters:

**Official Chrome Managed Policies** (per Google's documentation):
- Chrome supports MDM-style restrictions on Android
- These are delivered via `DevicePolicyManager.setApplicationRestrictions()` using a Bundle
- The Bundle contains Chrome-specific policy keys

**Critical Question**: Does Chrome accept **URLBlocklist** as a managed restriction on Android?

### Chrome Policy Reference

According to Google's official Chrome enterprise policies:
- **URLBlocklist** / **URLAllowlist** policies exist for Chrome
- These are documented for **Windows, macOS, and Linux**
- **Android support**: Chrome on Android **DOES NOT** have native support for URLBlocklist through managed configurations (as of 2026)

**Key Finding**: Chrome on Android supports managed configurations, but **URLBlocklist is not among the available Chrome managed policies for Android**. The URLBlocklist feature is primarily a desktop/web platform feature.

### Available Chrome Managed Policies on Android

Chrome on Android supports restrictions like:
- `DisableSSLCertificateChecks` - Disable SSL validation
- `CookiesBlockedForUrls` - Block cookies on specific URLs
- `ImagesBlockedForUrls` - Block images on specific URLs
- `JavaScriptBlockedForUrls` - Disable JavaScript on URLs
- `PopupsBlockedForUrls` - Block popups on URLs
- `SafeBrowsingProtectionLevel` - Safe Browsing level
- `AllowedDomainsForApps` - Restrict Chrome access to certain domains

**CRITICAL LIMITATION**: None of these policies completely **block navigation** to a URL in Chrome. They restrict features (cookies, images, JS) but do not prevent the user from reaching the website.

---

## PART 3: ANDROID CHROME INTENT FILTERS & BROWSER CONTENT PROVIDERS

### Alternative Mechanism: App Links + Default Browser Handler

On Android 12+, there is an API (`setDefaultBrowser()` in DevicePolicyManager) that allows Device Owner to set the default browser. However, this does not provide URL blocking capabilities.

### Custom Chrome Intent Interception?

Chrome does **not** expose hooks for URL interception from a Device Owner app. The VPN mechanism was developed precisely because there is no official API for policy-level URL blocking in Chrome on Android.

---

## PART 4: FEASIBILITY ASSESSMENT

### Can Device Owner + Chrome Managed Policy Block URLs?

**Answer: NO** — Not through official, supported mechanisms on Android 15/API 35.

**Why**:
1. Chrome on Android does not support URLBlocklist managed policy
2. Available Chrome managed policies restrict features, not navigation
3. No official Device Owner API exists for intercepting/blocking URLs in Chrome
4. Chrome does not expose content filtering hooks to external apps

### What Device Owner CAN do:

- ✅ Force specific apps to be disabled
- ✅ Manage Chrome's features (cookies, JS, images) on specific domains
- ✅ Force-close apps
- ✅ Restrict what apps can be installed
- ✅ Enforce system-wide security policies
- ❌ Block specific URLs in Chrome
- ❌ Intercept Chrome navigation events
- ❌ Force redirects in Chrome

---

## PART 5: ACTUAL TECHNICAL LIMITATION

The VPN approach was used because:

1. **Network-level blocking is the only way**: To intercept HTTPS traffic (Chrome's DoH), you need network-level access.
2. **App-level policies don't work**: Chrome doesn't expose hooks to external app policies for URL blocking.
3. **Root is not an option**: Without root, you can't modify system hosts file or firewall rules.
4. **Device Owner alone is insufficient**: Device Owner has high privileges but no way to intercept Chrome's network requests.

---

## PART 6: POSSIBLE WORKAROUNDS (NOT RECOMMENDED FOR THIS POC)

### 1. **Force-Disable Chrome + Install Managed Browser**
- Disable system Chrome
- Install an enterprise browser that supports policies (e.g., Samsung Secure Folder, corporate browsers)
- Problem: Not practical for consumer device; user can re-enable Chrome

### 2. **DNS Configuration via Device Owner**
- Some Device Owner implementations can set system DNS
- Problem: Chrome's DoH bypasses system DNS; already proven with current VPN

### 3. **Network Monitoring at Device Owner Level**
- Device Owner can potentially monitor network interfaces
- Problem: Still requires VPN or root to intercept/block; Device Owner alone cannot do this

### 4. **AccessibilityService + Chrome App Detection** (Current Approach)
- This is what's being used already
- Works but requires active interception

---

## PART 7: RECOMMENDATION FOR PHASE L POC

### Decision: DO NOT PROCEED with Device Owner + Chrome URLBlocklist POC

**Rationale**:
1. Official Chrome policies do not support URLBlocklist on Android
2. Device Owner cannot intercept Chrome network requests
3. No legitimate API path exists for policy-level URL blocking in Chrome
4. Research confirms this is a known Android/Chrome limitation

### Alternative Investigation Path (if needed):

If the goal is to find a non-VPN website blocking solution:

**Option A**: Use a managed browser (not Chrome) via Device Owner
**Option B**: Accept that network-level (VPN) blocking is necessary for Chrome on Android
**Option C**: Continue with current VPN + fix the "internet breaks" bug

---

## PART 8: ACTUAL ISSUE WITH CURRENT VPN

The statement **"VPN blocking works but internet breaks"** suggests:
- VPN is established but routing is broken
- DNS forwarding may be failing
- Network bypass (protect()) may not be working correctly

**This is a BUG IN THE VPN IMPLEMENTATION, not a fundamental limitation.**

The fix should be:
1. Debug VPN packet routing
2. Verify DNS forwarding is actually working
3. Check if protected sockets are properly bypassing VPN
4. Test on actual device with logcat

---

## CONCLUSION (PART 1)

**Device Owner + Chrome URLBlocklist is NOT viable because Chrome on Android does not support URLBlocklist managed policy through any official API.**

**Next steps**:
- Either: Proceed with VPN implementation bug fixes (PART K continuation)
- Or: Accept that network-level blocking (VPN) is required and focus on fixing the internet breakage bug

**NOT PROCEEDING** with Device Owner implementation as originally planned, since the underlying assumption (Chrome accepts URLBlocklist policy) is **FALSE**.

---

## References

- Android DevicePolicyManager: https://developer.android.com/reference/android/app/admin/DevicePolicyManager
- Chrome Enterprise Policies: https://chromeenterprise.google/policies/
- Android Management API: https://developers.google.com/android/management
- Chrome Managed Configurations (Android): Limited to non-URL-blocking policies

