# AI Guardian — Complete Project Flow & Architecture

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack & Dependencies](#2-tech-stack--dependencies)
3. [Project Structure](#3-project-structure)
4. [App Startup Flow](#4-app-startup-flow)
5. [Navigation Architecture](#5-navigation-architecture)
6. [Dashboard — Live Clock, Greeting & Last-Fap Timer](#6-dashboard--live-clock-greeting--last-fap-timer)
7. [Authentication System](#7-authentication-system)
8. [Flutter ↔ Native Bridge (MethodChannel / EventChannel)](#8-flutter--native-bridge)
9. [AccessibilityService — The Core Engine](#9-accessibilityservice--the-core-engine)
10. [Policy Engine — Allow/Block Decisions](#10-policy-engine--allowblock-decisions)
11. [App Blocking & Enforcement](#11-app-blocking--enforcement)
12. [Smart Restrictions — Schedules & Daily Limits](#12-smart-restrictions--schedules--daily-limits)
13. [Usage Tracking](#13-usage-tracking)
14. [Foreground Monitor — Boundary Enforcement](#14-foreground-monitor--boundary-enforcement)
15. [Content Filtering — Word/Phrase Blocking](#15-content-filtering--wordphrase-blocking)
16. [Website/Domain Blocking](#16-websitedomain-blocking)
17. [VPN-Based Domain Blocker](#17-vpn-based-domain-blocker)
18. [Uninstall Protection](#18-uninstall-protection)
19. [Settings Accessibility Guard](#19-settings-accessibility-guard)
20. [Device Administrator & Device Owner](#20-device-administrator--device-owner)
21. [SQLite Database Schema](#21-sqlite-database-schema)
22. [Password System — Two Separate Credentials](#22-password-system--two-separate-credentials)
23. [Android Resources & Layouts](#23-android-resources--layouts)
24. [Testing](#24-testing)
25. [Complete Data Flow Diagrams](#25-complete-data-flow-diagrams)

---

## 1. Project Overview

AI Guardian is a **Flutter + Kotlin hybrid Android app** that acts as a digital wellness / parental-control tool. It detects which app is in the foreground using Android's AccessibilityService, evaluates policies (allow/block), and enforces restrictions by showing a block screen. It also blocks websites via a local VPN, filters objectionable content in real time, protects itself from uninstall, and guards its own AccessibilityService toggle in Settings.

**Key principles:**
- **100% local** — no cloud, no network calls, no AI APIs, no Firebase
- **Fail-safe/fail-closed** — unknown packages are ALLOWed; security checks deny on error
- **Self-protection** — AI Guardian never blocks itself
- **Deterministic** — same input always produces the same output

---

## 2. Tech Stack & Dependencies

### Flutter (Dart)
| Dependency | Version | Purpose |
|---|---|---|
| `flutter` | SDK | UI framework |
| `go_router` | ^14.2.0 | Declarative routing |
| `shared_preferences` | ^2.5.5 | Local key-value persistence (last-fap timestamp) |
| `cupertino_icons` | ^1.0.6 | iOS-style icons |
| `flutter_test` | SDK | Testing |
| `flutter_lints` | ^4.0.0 | Lint rules |
| `very_good_analysis` | ^5.1.0 | Stricter lint rules |

### Native (Kotlin/Android)
| Component | Purpose |
|---|---|
| Android SDK 36 (min 24) | Target platform |
| SQLite (via `SQLiteOpenHelper`) | Local database |
| `AccessibilityService` | Foreground app detection, content filtering, website blocking |
| `VpnService` | Local VPN for DNS-based domain blocking |
| `DevicePolicyManager` | Device Admin / Device Owner capabilities |
| JUnit 4.13.2 | Kotlin unit tests |

---

## 3. Project Structure

```
AI-Guardian/
├── assets/
│   ├── app_password.txt                    # Main app password
│   └── accessibility_password.txt          # Separate accessibility guard password
│
├── lib/                                    # Flutter/Dart code
│   ├── main.dart                           # Entry point, loads passwords + LastFapStorage
│   ├── app/
│   │   └── router.dart                     # GoRouter configuration
│   ├── core/
│   │   ├── constants/app_constants.dart    # Route paths, app metadata
│   │   ├── theme/app_theme.dart            # Material 3 theme
│   │   └── widgets/main_scaffold.dart      # Bottom navigation bar (4 tabs)
│   ├── features/
│   │   ├── auth/
│   │   │   ├── auth_gate.dart              # Password gate for app launch
│   │   │   ├── password_screen.dart        # Password entry UI
│   │   │   ├── password_service.dart       # Loads/verifies app_password.txt
│   │   │   ├── accessibility_password_service.dart  # Loads/verifies accessibility_password.txt
│   │   │   └── accessibility_security_screen.dart   # Accessibility password entry UI
│   │   ├── dashboard/
│   │   │   ├── screens/dashboard_screen.dart   # Live dashboard with clock + timer
│   │   │   ├── services/last_fap_storage.dart  # SharedPreferences persistence
│   │   │   └── utils/dashboard_utils.dart      # Greeting, time, date, elapsed formatters
│   │   ├── restrictions/
│   │   │   ├── screens/restrictions_screen.dart    # App + Website restriction tabs
│   │   │   ├── screens/restriction_settings_screen.dart  # Per-app settings
│   │   │   ├── screens/content_filter_screen.dart  # Word/phrase filter UI
│   │   │   └── screens/domain_restrictions_screen.dart   # Domain blocking UI
│   │   ├── settings/
│   │   │   └── screens/settings_screen.dart    # App settings, Device Admin, etc.
│   │   ├── analytics/
│   │   │   └── screens/analytics_screen.dart   # Usage analytics
│   │   └── coach/
│   │       └── screens/coach_screen.dart       # Unused (removed from navigation)
│   ├── platform/
│   │   ├── android_platform_service.dart    # Centralized MethodChannel/EventChannel calls
│   │   ├── platform_channels.dart           # Channel definitions
│   │   └── models/                          # Data models for platform responses
│   │       ├── platform_info.dart
│   │       ├── app_version.dart
│   │       ├── accessibility_status.dart
│   │       ├── foreground_app_event.dart
│   │       ├── installed_app.dart
│   │       ├── policy_model.dart
│   │       ├── domain_model.dart
│   │       └── device_owner_status.dart
│   └── (onboarding/ deleted)
│
├── android/app/src/main/kotlin/com/aiguardian/ai_guardian/
│   ├── MainActivity.kt                     # Flutter engine host, VPN permission flow
│   ├── platform/
│   │   └── PlatformChannelHandler.kt       # Handles all 40+ MethodChannel calls
│   ├── service/
│   │   ├── AIGuardianAccessibilityService.kt  # Core: ~2100 lines, detection + enforcement
│   │   └── DomainBlockerVpnService.kt      # Local VPN: ~1150 lines, DNS interception
│   ├── policy/
│   │   ├── Policy.kt                       # Data class: packageName, action, schedules, dailyLimit
│   │   ├── PolicyAction.kt                 # Enum: ALLOW, BLOCK
│   │   ├── PolicyResult.kt                 # Result of policy evaluation
│   │   ├── PolicyEngine.kt                 # Deterministic evaluator with precedence rules
│   │   ├── RestrictionSchedule.kt          # Time window (minutes from midnight)
│   │   ├── ScheduleEvaluator.kt            # Checks if current time is within schedule
│   │   ├── DailyLimit.kt                   # Daily usage limit
│   │   ├── UsageTracker.kt                 # Tracks foreground sessions + daily totals
│   │   ├── ForegroundMonitor.kt            # Monitors restricted apps, schedules boundaries
│   │   └── DomainPolicy.kt                 # Domain normalization + validation
│   ├── enforcement/
│   │   ├── EnforcementManager.kt           # Cooldown logic, enforcement state
│   │   ├── BlockActivity.kt                # Block screen shown when app is blocked
│   │   ├── UninstallGuardActivity.kt       # Password gate for uninstall attempts
│   │   └── SettingsAccessibilityGuardActivity.kt  # Password gate for disabling service
│   ├── storage/
│   │   ├── PolicyDatabaseHelper.kt         # SQLite schema, migrations (v1→v5)
│   │   ├── PolicyRepository.kt             # CRUD for policies, schedules, usage sessions
│   │   ├── DomainRepository.kt             # CRUD for domain blocking rules
│   │   └── SettingsStorage.kt              # Empty placeholder
│   ├── contentfilter/
│   │   ├── ContentFilterEngine.kt          # Text matching engine (EXACT + CONTAINS)
│   │   ├── ContentFilterRule.kt            # Rule data class
│   │   ├── ContentFilterMatchMode.kt       # Enum: CONTAINS, EXACT
│   │   └── ContentFilterRepository.kt      # CRUD for filter rules
│   └── admin/
│       ├── DeviceOwnerReceiver.kt          # DeviceAdminReceiver subclass
│       └── DeviceOwnerManager.kt           # Device Admin/Owner capabilities
│
├── android/app/src/main/res/
│   ├── layout/
│   │   ├── activity_block.xml              # Block enforcement screen
│   │   ├── activity_uninstall_guard.xml    # Uninstall password gate
│   │   └── activity_settings_accessibility_guard.xml  # Settings guard password gate
│   ├── xml/
│   │   ├── accessibility_service_config.xml  # Service config (event types, etc.)
│   │   └── device_admin_receiver.xml         # Device admin policies
│   ├── values/
│   │   ├── strings.xml                     # App name, service description
│   │   └── styles.xml                      # LaunchTheme, NormalTheme
│   └── drawable/
│       └── launch_background.xml           # White splash background
│
└── test/
    ├── features/auth/accessibility_password_service_test.dart
    └── features/dashboard/
        ├── utils/dashboard_utils_test.dart
        └── services/last_fap_storage_test.dart
```

---

## 4. App Startup Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        main()                                   │
├─────────────────────────────────────────────────────────────────┤
│ 1. WidgetsFlutterBinding.ensureInitialized()                   │
│ 2. PasswordService.loadPassword()          ← assets/app_password.txt │
│ 3. AccessibilityPasswordService.loadPassword() ← assets/accessibility_password.txt │
│ 4. LastFapStorage.load()                   ← SharedPreferences │
│ 5. runApp(AiGuardianApp())                                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│              MaterialApp.router (GoRouter)                      │
│  initialLocation: /dashboard                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    Dashboard Screen                             │
│  ┌──────────────────────────────────────┐                      │
│  │ Good Evening                         │  ← dynamic greeting  │
│  │ 09:21:37 PM                          │  ← live clock (1s)   │
│  │ Thursday, September 24, 2026         │  ← live date         │
│  │                                      │                      │
│  │ Time since last reset                │                      │
│  │ 3d 5h 21m 37s                        │  ← elapsed timer     │
│  │                                      │                      │
│  │ [FAPPED AGAIN]                       │  ← resets timer      │
│  └──────────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘
```

**Simultaneously**, the Android native side initializes in `MainActivity.configureFlutterEngine()`:

```
┌─────────────────────────────────────────────────────────────────┐
│              MainActivity.configureFlutterEngine()               │
├─────────────────────────────────────────────────────────────────┤
│ 1. Create PlatformChannelHandler                               │
│ 2. Create PolicyDatabaseHelper → PolicyRepository              │
│ 3. Create UsageTracker(repository)                             │
│ 4. Create PolicyEngine(repository) + assign usageTracker       │
│ 5. Inject into AIGuardianAccessibilityService:                 │
│    - policyEngine = policyEngine                               │
│    - usageTracker = usageTracker                               │
│ 6. Create ContentFilterRepository + ContentFilterEngine        │
│ 7. Load enabled rules into engine                              │
│ 8. Inject into AIGuardianAccessibilityService:                 │
│    - contentFilterEngine = engine                              │
│    - contentFilterRepository = repository                      │
│ 9. Register MethodChannel handler (delegates to PlatformChannelHandler) │
│ 10. Register EventChannel handler (for native → Flutter events)│
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Navigation Architecture

**Bottom Navigation** — 4 tabs (via `MainScaffold`):

| Index | Tab | Route | Screen |
|---|---|---|---|
| 0 | Dashboard | `/dashboard` | `DashboardScreen` |
| 1 | Restrictions | `/restrictions` | `RestrictionsScreen` |
| 2 | Analytics | `/analytics` | `AnalyticsScreen` |
| 3 | Settings | `/settings` | `SettingsScreen` |

**Restrictions Screen** has two tabs:
- **Apps** — list installed apps with restriction toggles
- **Websites** — domain blocking with VPN start/stop

**GoRouter** wraps all routes inside a `ShellRoute` that provides the `MainScaffold` (bottom nav). The dashboard is the initial route (no onboarding/welcome screen).

---

## 6. Dashboard — Live Clock, Greeting & Last-Fap Timer

### Dynamic Greeting
```
getGreeting(DateTime now) → String
  05:00–11:59 → "Good Morning"
  12:00–16:59 → "Good Afternoon"
  17:00–20:59 → "Good Evening"
  21:00–04:59 → "Good Night"
```

### Live Clock
- `Timer.periodic(Duration(seconds: 1))` updates `_now` every second
- `formatTime(now)` → "09:21:37 PM" (12-hour with seconds)
- Timer cancelled in `dispose()`, refreshed on `AppLifecycleState.resumed`

### Live Date
- `formatDate(now)` → "Thursday, September 24, 2026"
- Derived from the same `_now` DateTime

### Last-Fap Timer
- **Seed timestamp**: `2026-09-21 16:00:00` (Asia/Karachi)
- **Storage**: `LastFapStorage` wraps `SharedPreferences` (key: `last_fap_timestamp`, stored as epoch millis)
- **Elapsed**: `now.difference(lastFapTimestamp)` → `formatElapsed()` → "3d 5h 21m 37s"
- **FAPPED AGAIN button**: saves `DateTime.now()` to SharedPreferences, resets timer
- **Survives restart**: loaded at startup via `LastFapStorage.load()`

---

## 7. Authentication System

### Two Separate Password Files

| Password | File | Used By |
|---|---|---|
| App password | `assets/app_password.txt` | Flutter `PasswordScreen`, `UninstallGuardActivity` |
| Accessibility password | `assets/accessibility_password.txt` | `AccessibilitySecurityScreen`, `SettingsAccessibilityGuardActivity` |

### Flutter Password Services

**`PasswordService`** (app password):
- `loadPassword()` — reads from `assets/app_password.txt` via `rootBundle`
- `hasPassword()` — fail-closed: returns true if not loaded
- `verify(password)` — fail-closed: returns false if not loaded

**`AccessibilityPasswordService`** (accessibility password):
- Identical pattern but reads `assets/accessibility_password.txt`
- Completely independent — no cross-referencing

### Native Password Verification

Both `UninstallGuardActivity` and `SettingsAccessibilityGuardActivity` read their respective password files directly from Android assets (`assets.open(...)`). This is necessary because they are native Activities launched from the AccessibilityService context (no Flutter engine available).

---

## 8. Flutter ↔ Native Bridge

### MethodChannel (`com.aiguardian.ai_guardian/method`)

Flutter calls → Native handles. Over 40 methods organized by feature:

| Category | Methods |
|---|---|
| Platform | `getPlatformInfo`, `checkAccessibilityStatus`, `openAccessibilitySettings`, `getAppVersion` |
| Auth | `verifyPassword`, `hasPassword` |
| Apps | `getInstalledApps` |
| Policies | `getPolicies`, `getPolicy`, `savePolicy`, `updatePolicy`, `deletePolicy`, `setPolicyEnabled` |
| Schedules | `saveSchedule`, `getAllSchedules`, `addSchedule`, `updateSchedule`, `deleteSchedule`, `toggleScheduleEnabled` |
| Daily Limits | `saveDailyLimit`, `getUsageToday`, `getFullPolicy` |
| Domains | `getAllDomains`, `saveDomain`, `deleteDomain`, `setDomainEnabled` |
| VPN | `startDomainBlocking`, `stopDomainBlocking` |
| Content Filter | `getAllContentFilterRules`, `saveContentFilterRule`, `updateContentFilterRule`, `deleteContentFilterRule`, `setContentFilterRuleEnabled`, `setMasterContentFilterEnabled`, `isMasterContentFilterEnabled` |
| Device Admin | `getDeviceOwnerStatus`, `requestDeviceAdmin`, `removeDeviceAdmin` |
| Device Owner | `applyUninstallProtection`, `removeUninstallProtection`, `checkUninstallProtection` |
| Chrome | `applyChromeBlocklist`, `getChromePolicy`, `clearChromePolicy` |
| Misc | `refreshBlockedDomains` |

### EventChannel (`com.aiguardian.ai_guardian/event`)

Native → Flutter streaming. The AccessibilityService emits events:
- `foregroundAppChanged` — contains `packageName`, `policyAction`, `reason`

---

## 9. AccessibilityService — The Core Engine

**File**: `AIGuardianAccessibilityService.kt` (~2100 lines)

### Event Types Listened
From `accessibility_service_config.xml`:
- `TYPE_WINDOW_STATE_CHANGED` — foreground app detection
- `TYPE_VIEW_TEXT_CHANGED` — text content filtering (user input)
- `TYPE_WINDOW_CONTENT_CHANGED` — text content filtering (content updates)

### Event Processing Flow

```
onAccessibilityEvent(event)
    │
    ├── Periodic cleanup (every 500 events)
    │
    ├── Uninstall guard detection (before routing)
    │   └── detectUninstallAttempt() → triggerUninstallGuard()
    │
    ├── Settings accessibility guard detection
    │   └── detectAiGuardianAccessibilityDetailsPage() → triggerSettingsAccessibilityGuard()
    │
    ├── Settings diagnostic logging (bounded)
    │
    ├── Route by eventType:
    │   ├── TYPE_WINDOW_STATE_CHANGED → handleWindowStateChanged()
    │   │   ├── Skip if same package
    │   │   ├── End previous UsageTracker session
    │   │   ├── Start new UsageTracker session
    │   │   ├── ForegroundMonitor.onForegroundAppChanged()
    │   │   │   ├── PolicyEngine.evaluate(packageName)
    │   │   │   ├── If BLOCK → EnforcementManager.shouldEnforce() → BlockActivity.launch()
    │   │   │   └── If ALLOW → calculate next boundary → schedule Handler.postDelayed
    │   │   └── Emit foregroundAppChanged event to Flutter
    │   │
    │   └── TYPE_VIEW_TEXT_CHANGED / TYPE_WINDOW_CONTENT_CHANGED → handleTextEvent()
    │       ├── Skip own package events
    │       ├── Skip if master switch off or no rules
    │       ├── Extract text from event + node tree traversal
    │       ├── ContentFilterEngine.evaluateText()
    │       └── On match → debounce → BlockActivity.launch()
```

### Website Blocking (inside AccessibilityService)

Only active for Chrome (`com.android.chrome`):
1. Detect Chrome page state (NEW_TAB, ADDRESS_BAR, SEARCH_RESULTS, WEB_PAGE, INCOGNITO_WEB_PAGE)
2. Extract URL candidates from the accessibility tree
3. Verify the domain is actually the loaded page (not a shortcut/suggestion)
4. Check against in-memory blocked domains (exact + subdomain match)
5. On match → debounce → GLOBAL_ACTION_BACK → BlockActivity

---

## 10. Policy Engine — Allow/Block Decisions

### Rule Precedence (first match wins)

```
1. Null/blank package    → ALLOW  (fail-safe)
2. AI Guardian package   → ALLOW  (self-protection)
3. Repository unavailable→ ALLOW  (fail-safe)
4. No policy found       → ALLOW  (fail-safe)
5. Policy disabled       → ALLOW
6. Schedule active       → BLOCK  (overrides base action during window)
7. Daily limit exceeded  → BLOCK  (overrides base action when exceeded)
8. Database error        → ALLOW  (fail-safe)
9. Otherwise             → policy.baseAction (ALLOW or BLOCK)
```

### Policy Data Model

```kotlin
data class Policy(
    packageName: String,
    action: PolicyAction,        // ALLOW or BLOCK
    enabled: Boolean,
    schedules: List<RestrictionSchedule>,  // Multiple time windows
    dailyLimit: DailyLimit?,               // Optional daily limit
    dailyLimitEnabled: Boolean,
)
```

### In-Memory Cache

`PolicyEngine` maintains `policyCache: Map<String, Policy>` for O(1) lookups during frequent app switching. Refreshed from SQLite on startup and after bulk changes.

---

## 11. App Blocking & Enforcement

### Flow

```
AccessibilityService detects foreground app change
    ↓
PolicyEngine.evaluate(packageName) → PolicyResult
    ↓
If action == BLOCK:
    ↓
EnforcementManager.shouldEnforce(packageName, BLOCK)
    ├── Check: not own package
    ├── Check: cooldown elapsed (1500ms)
    └── Returns true/false
    ↓
If true → BlockActivity.launch(context, packageName, reason)
    ↓
BlockActivity displays:
    ├── "App Blocked" title
    ├── Contextual message (schedule/limit/policy)
    ├── Package name
    ├── Intervention text (from assets/voice_intervention_text.txt)
    └── "Back to AI Guardian" button → opens MainActivity
    ↓
BlockActivity.onDestroy() → EnforcementManager.resetForPackage()
    (allows re-blocking if app returns to foreground)
```

---

## 12. Smart Restrictions — Schedules & Daily Limits

### Schedules

- Times stored as **minutes from midnight** (0–1439)
- Supports **same-day windows** (e.g., 1080–1140 = 6 PM–9 PM)
- Supports **overnight windows** (e.g., 1380–420 = 10 PM–7 AM)
- **Multiple schedules per app** (stored in `app_schedules` table)
- **OR logic**: any active schedule triggers BLOCK

```kotlin
ScheduleEvaluator.isWithinSchedule(schedule, currentMinutes)
    start <= end → currentMinutes in start..end        // same-day
    start > end  → current >= start || current <= end   // overnight
```

### Daily Limits

- Limit stored in minutes
- Usage tracked via `UsageTracker` (sessions persisted to SQLite)
- `hasExceededLimit()` compares today's total usage against limit
- When exceeded, PolicyEngine returns BLOCK regardless of base action

### Foreground Monitor — Boundary Scheduling

When a restricted app enters the foreground with ALLOW action but has an active schedule or daily limit:

1. Calculate the **next enforcement boundary** (earliest of: schedule end, daily limit hit)
2. Schedule a single `Handler.postDelayed` to fire at that time
3. When the timer fires:
   - Re-read latest policy from repository
   - Re-evaluate with PolicyEngine
   - If now BLOCK → launch BlockActivity
   - If still ALLOW → calculate next boundary

**Guarantees**: One pending task at most. Cancelled when app leaves foreground or policy changes. No busy-waiting, no polling loops.

---

## 13. Usage Tracking

### UsageTracker

- Tracks the **active foreground session** (packageName + startTime)
- On app switch: ends previous session, starts new one
- **Minimum session**: 1 second (ignores rapid switches)
- Sessions persisted to SQLite `usage_sessions` table

### Daily Usage Calculation

```
getUsageTodayMs(packageName):
    1. Check in-memory cache (avoids DB queries)
    2. If cache miss → SUM(duration_ms) FROM usage_sessions
       WHERE package_name = ? AND date = YYYY-MM-DD
    3. Add current active session duration (if applicable)
    4. Return total milliseconds
```

---

## 14. Foreground Monitor — Boundary Enforcement

**Purpose**: When a restricted app is in the foreground with ALLOW action but has time-based or usage-based restrictions, schedule enforcement for the exact moment the restriction kicks in.

```
onForegroundAppChanged(packageName):
    1. Cancel any previous monitoring
    2. PolicyEngine.evaluate() → result
    3. If BLOCK → return immediately (no monitoring needed)
    4. If ALLOW → findPolicy() → calculateNextBoundary()
    5. If boundary exists → scheduleBoundary(packageName, delayMs)
    6. If no boundary → stop monitoring

calculateNextBoundary(policy, packageName):
    For each enabled schedule:
        calculateScheduleBoundary() → boundaryMs
    If dailyLimitEnabled:
        calculateLimitBoundary() → boundaryMs
    Return earliest boundary
```

---

## 15. Content Filtering — Word/Phrase Blocking

### ContentFilterEngine

- Rules loaded from SQLite into memory
- Two match modes:
  - **EXACT**: `normalizedText == phrase` → O(1) HashSet lookup
  - **CONTAINS**: `normalizedText.contains(phrase)` → linear scan
- Master switch: when off, all matching is bypassed
- Self-exclusion: AI Guardian's own text is never filtered
- Performance: text truncated to 10,000 characters max

### Content Filtering Flow

```
AccessibilityService.handleTextEvent(event):
    1. Skip own package, skip if master off or no rules
    2. Extract text from:
       - event.text
       - event.contentDescription
       - Node tree traversal (rootInActiveWindow)
    3. ContentFilterEngine.evaluateText(text, packageName)
    4. If matched:
       a. Debounce per (package, phrase) pair
       b. Launch BlockActivity with reason
```

### ContentFilterRule

```kotlin
data class ContentFilterRule(
    id: Long,
    phrase: String,           // normalized: trimmed + lowercased
    enabled: Boolean,
    matchMode: ContentFilterMatchMode,  // CONTAINS or EXACT
    createdAt: Long,
    updatedAt: Long,
)
```

---

## 16. Website/Domain Blocking

### Two-Layer Approach

**Layer 1: AccessibilityService-based (Phase 3B)**
- Only active for Chrome (`com.android.chrome`)
- Monitors Chrome page state changes
- Extracts URL from accessibility tree
- Checks against in-memory blocked domain set
- On match: GLOBAL_ACTION_BACK + BlockActivity

**Layer 2: VPN-based (Phase K)**
- Blocks DNS queries for restricted domains
- Works for ALL apps (not just Chrome)
- Intercepts even Chrome's DNS-over-HTTPS (full-tunnel VPN)
- Non-DNS traffic forwarded through protected sockets

### Domain Normalization

```kotlin
DomainPolicy.normalize("https://www.YouTube.com/path?q=1")
→ "youtube.com"

Rules:
1. Strip protocol (http://, https://)
2. Strip path, query, fragment, port
3. Remove trailing dot
4. Lowercase
5. Strip www. prefix
6. Validate: ≥2 labels, no spaces, no special chars
```

---

## 17. VPN-Based Domain Blocker

**File**: `DomainBlockerVpnService.kt` (~1150 lines)

### Architecture

```
All device traffic → TUN interface (10.0.0.2)
    ↓
DomainBlockerVpnService reads IP packets
    ↓
processPacket() → parse IPv4 header
    ├── UDP port 53 (DNS):
    │   ├── Extract queried domain from DNS wire format
    │   ├── If domain blocked → return NXDOMAIN response
    │   └── If allowed → forward to upstream 8.8.8.8
    │
    ├── Other UDP:
    │   └── Forward via DatagramSocket with protect() (bypasses VPN)
    │
    └── TCP:
        └── Full TCP state machine:
            SYN → connect to real server (protected socket)
            DATA → forward bidirectionally
            FIN/RST → cleanup
```

### Key Features

- **Full-tunnel**: ALL traffic routes through TUN (needed for Chrome DoH)
- **Foreground service** with persistent notification
- **Max connections**: 64 TCP, 64 UDP relays
- **Dynamic updates**: `updateBlockedDomains()` updates in-memory set (ConcurrentHashMap)

---

## 18. Uninstall Protection

### Detection Flow

```
AccessibilityService.onAccessibilityEvent()
    ↓
detectUninstallAttempt(event):
    Package: com.google.android.packageinstaller
    Multi-signal detection (≥2 signals required):
        Signal 1: AI Guardian name visible in text
        Signal 2: Uninstall prompt text ("Do you want to uninstall...")
        Signal 3: AlertDialog class name
        Signal 4: Cancel/OK buttons visible
    ↓
If detected:
    1. settingsAccessibilityGuardActive = true
    2. performGlobalAction(GLOBAL_ACTION_BACK) → interrupts uninstall
    3. UninstallGuardActivity.launch()
```

### Password Gate

- Reads `assets/app_password.txt` (same as Flutter PasswordService)
- Correct password → opens AI Guardian (uninstall interrupted)
- Wrong password → error message
- Back/Cancel → opens AI Guardian (uninstall still interrupted)
- `onDestroy()` → `clearUninstallGuard()` (allows future detection)

---

## 19. Settings Accessibility Guard

### Purpose

Protects the AI Guardian AccessibilityService toggle in Android Settings. When the user navigates to the AI Guardian accessibility details page, a password gate appears.

### Detection Flow

```
AccessibilityService.onAccessibilityEvent()
    ↓
detectAiGuardianAccessibilityDetailsPage():
    Package: com.android.settings
    MANDATORY: "AI Guardian" text visible in window
    AND at least one accessibility context signal:
        - Accessibility class name in window hierarchy
        - Switch/CheckBox toggle node in node tree
    ↓
If detected AND not already guarded AND not in cooldown (5s):
    triggerSettingsAccessibilityGuard():
        1. settingsAccessibilityGuardActive = true
        2. NO GLOBAL_ACTION_BACK (Settings screen stays underneath)
        3. SettingsAccessibilityGuardActivity.launch()
```

### Password Gate

- Reads `assets/accessibility_password.txt` (SEPARATE from app_password)
- Correct password → `finish()` → reveals underlying Settings toggle screen
- Wrong password → error message
- Back/Cancel → `finish()` → returns to Settings
- `onDestroy()` → `clearSettingsAccessibilityGuard()`

---

## 20. Device Administrator & Device Owner

### Two Levels

| Feature | Device Admin | Device Owner |
|---|---|---|
| Activation | User toggles in Settings | Requires provisioning at setup or ADB |
| Uninstall blocking | ❌ | ✅ `setUninstallBlocked()` |
| Chrome URL blocklist | ❌ | ✅ `setApplicationRestrictions()` |
| Normal admin operations | ✅ | ✅ |

### DeviceOwnerManager

- `isAdminActive()` — checks `DevicePolicyManager.isAdminActive()`
- `isDeviceOwner()` — checks `DevicePolicyManager.isDeviceOwnerApp()`
- `applyUninstallProtection()` — `setUninstallBlocked(packageName, true)`
- `applyChromeBlocklistPolicy()` — `setApplicationRestrictions("com.android.chrome", bundle)`
- `getStatus()` — returns full status map for Flutter UI

---

## 21. SQLite Database Schema

**Database**: `ai_guardian.db` (version 5)

### Table: `policies`

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-increment |
| package_name | TEXT UNIQUE | Android package name |
| action | TEXT | "ALLOW" or "BLOCK" |
| enabled | INTEGER | 0 or 1 |
| created_at | INTEGER | Epoch millis |
| updated_at | INTEGER | Epoch millis |
| schedule_enabled | INTEGER | Legacy (always 0) |
| schedule_start | INTEGER | Legacy (NULL) |
| schedule_end | INTEGER | Legacy (NULL) |
| daily_limit_enabled | INTEGER | 0 or 1 |
| daily_limit_minutes | INTEGER | NULL = no limit |

### Table: `app_schedules`

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-increment |
| package_name | TEXT | References policies.package_name |
| start_minutes | INTEGER | 0–1439 |
| end_minutes | INTEGER | 0–1439 |
| enabled | INTEGER | 0 or 1 |
| created_at | INTEGER | Epoch millis |
| updated_at | INTEGER | Epoch millis |

### Table: `usage_sessions`

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-increment |
| package_name | TEXT | |
| start_time | INTEGER | Epoch millis |
| end_time | INTEGER | Epoch millis |
| duration_ms | INTEGER | |
| date | TEXT | "YYYY-MM-DD" |

### Table: `domains`

| Column | Type | Notes |
|---|---|---|
| domain | TEXT PK | Normalized domain |
| enabled | INTEGER | 0 or 1 |
| created_at | INTEGER | Epoch millis |
| updated_at | INTEGER | Epoch millis |

### Table: `content_filter_rules`

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-increment |
| phrase | TEXT | Normalized (lowercase, trimmed) |
| enabled | INTEGER | 0 or 1 |
| match_mode | TEXT | "CONTAINS" or "EXACT" |
| created_at | INTEGER | Epoch millis |
| updated_at | INTEGER | Epoch millis |

### Migration History

| From → To | Changes |
|---|---|
| v1 → v2 | Added schedule/daily_limit columns to policies, created usage_sessions |
| v2 → v3 | Created domains table |
| v3 → v4 | Created content_filter_rules table |
| v4 → v5 | Created app_schedules table, migrated single schedules from policies |

---

## 22. Password System — Two Separate Credentials

```
┌─────────────────────────────────────────────────────────────┐
│                    PASSWORD SEPARATION                       │
├────────────────────────┬────────────────────────────────────┤
│  APP PASSWORD           │  ACCESSIBILITY PASSWORD            │
│  assets/app_password.txt│  assets/accessibility_password.txt │
├────────────────────────┼────────────────────────────────────┤
│  Flutter:               │  Flutter:                          │
│   PasswordService       │   AccessibilityPasswordService     │
│   PasswordScreen        │   AccessibilitySecurityScreen      │
│                         │                                    │
│  Native:                │  Native:                           │
│   UninstallGuardActivity│   SettingsAccessibilityGuardActivity│
│   (same file, same pwd) │   (separate file, separate pwd)   │
├────────────────────────┼────────────────────────────────────┤
│  Protects:              │  Protects:                         │
│   App launch (Flutter)  │   AccessibilityService toggle      │
│   Uninstall attempts    │   in Android Settings              │
└────────────────────────┴────────────────────────────────────┘
```

Both passwords are:
- Loaded once at startup
- Never stored in SharedPreferences, SQLite, logs, or any persistent location
- Verified against in-memory cache
- Fail-closed: errors deny access

---

## 23. Android Resources & Layouts

### activity_block.xml
- Title: "App Blocked"
- Message: contextual (schedule/limit/policy)
- Package name display
- Optional reason text
- Intervention text from `assets/voice_intervention_text.txt`
- "Back to AI Guardian" button

### activity_uninstall_guard.xml
- Lock icon
- "AI Guardian Protection" title
- Password input field
- Error message (hidden by default)
- Verify button → reads `app_password.txt`
- Cancel button → returns to AI Guardian

### activity_settings_accessibility_guard.xml
- Lock icon
- "Accessibility Security" title
- Password input field
- Error message (hidden by default)
- Verify button → reads `accessibility_password.txt`
- Cancel button → returns to Settings

### accessibility_service_config.xml
```xml
eventTypes="typeWindowStateChanged|typeViewTextChanged|typeWindowContentChanged"
canRetrieveWindowContent="true"
notificationTimeout="100"
feedbackType="feedbackGeneric"
```

---

## 24. Testing

### Flutter Tests

| File | Tests | Covers |
|---|---|---|
| `accessibility_password_service_test.dart` | 13 | Password loading, verification, fail-closed, separation from app password |
| `dashboard_utils_test.dart` | 25 | Greeting (4 time ranges), formatTime, formatDate, formatElapsed, edge cases |
| `last_fap_storage_test.dart` | 6 | Persistence, seed value, save/load, overwrite |

### Kotlin Unit Tests

| File | Tests | Covers |
|---|---|---|
| `SettingsAccessibilityGuardTest.kt` | 25 | Detection logic, false positives, AI Guardian name detection, own package exclusion, edge cases |

---

## 25. Complete Data Flow Diagrams

### App Blocking Flow

```
User opens blocked app
    ↓
Android → TYPE_WINDOW_STATE_CHANGED event
    ↓
AIGuardianAccessibilityService.onAccessibilityEvent()
    ↓
handleWindowStateChanged()
    ├── UsageTracker.startSession(packageName)
    ├── ForegroundMonitor.onForegroundAppChanged(packageName)
    │       ↓
    │   PolicyEngine.evaluate(packageName)
    │       ├── Cache lookup → policy found
    │       ├── Check: schedule active? → BLOCK
    │       ├── Check: daily limit exceeded? → BLOCK
    │       └── Return base action
    │       ↓
    │   If BLOCK:
    │       EnforcementManager.shouldEnforce()
    │           ↓
    │       BlockActivity.launch(context, packageName, reason)
    │           ↓
    │       User sees block screen
    │       "Back to AI Guardian" → MainActivity
    │       onDestroy() → resetForPackage()
    │
    └── Emit foregroundAppChanged to Flutter
```

### Domain Blocking Flow (VPN)

```
User opens website in any browser
    ↓
DNS query for domain.com → port 53
    ↓
Device routes through TUN (VPN)
    ↓
DomainBlockerVpnService reads IP packet
    ↓
processPacket() → handleUdp() → DNS port 53
    ↓
extractDnsQueryDomain() → "domain.com"
    ↓
isDomainBlocked("domain.com"):
    ├── Exact match in blockedDomains set?
    └── Parent domain match?
    ↓
BLOCKED → return NXDOMAIN response (domain not found)
ALLOWED → forward to 8.8.8.8 (upstream DNS)
```

### Content Filtering Flow

```
User types or views text in any app
    ↓
Android → TYPE_VIEW_TEXT_CHANGED or TYPE_WINDOW_CONTENT_CHANGED
    ↓
AIGuardianAccessibilityService.handleTextEvent()
    ├── Skip own package
    ├── Skip if master switch off
    ├── Skip if no rules loaded
    ├── Extract text: event.text + contentDescription + node tree
    ↓
ContentFilterEngine.evaluateText(text, packageName)
    ├── Truncate to 10,000 chars
    ├── Normalize: trim + lowercase
    ├── Check EXACT phrases (HashSet O(1))
    ├── Scan CONTAINS phrases (linear)
    ↓
MATCH → debounce per (package, phrase)
    ↓
BlockActivity.launch(context, packageName, "Content filter: '$phrase'")
```

### Uninstall Protection Flow

```
User long-presses AI Guardian → Uninstall
    ↓
Package Installer shows confirmation dialog
    ↓
AccessibilityService detects events from com.google.android.packageinstaller
    ↓
detectUninstallAttempt():
    Signal 1: "AI Guardian" text visible ✓
    Signal 2: Uninstall prompt text ✓
    ≥ 2 signals → DETECTED
    ↓
triggerUninstallGuard():
    1. performGlobalAction(GLOBAL_ACTION_BACK) → interrupts uninstall flow
    2. UninstallGuardActivity.launch()
    ↓
Password gate (app_password.txt)
    ├── Correct → open AI Guardian (uninstall interrupted)
    └── Wrong → error, remain on screen
```

### Settings Accessibility Guard Flow

```
User navigates: Settings → Accessibility → AI Guardian
    ↓
AccessibilityService detects events from com.android.settings
    ↓
detectAiGuardianAccessibilityDetailsPage():
    Signal 1: "AI Guardian" text visible ✓ (MANDATORY)
    Signal 2: Accessibility class OR toggle node ✓
    DETECTED
    ↓
triggerSettingsAccessibilityGuard():
    1. NO GLOBAL_ACTION_BACK (Settings screen stays underneath)
    2. SettingsAccessibilityGuardActivity.launch()
    ↓
Password gate (accessibility_password.txt)
    ├── Correct → finish() → reveals Settings toggle screen
    └── Wrong → error, remain on screen
```

---

*This document reflects the complete state of AI Guardian as of September 2026.*
