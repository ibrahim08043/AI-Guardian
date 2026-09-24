import 'dart:async';

import 'package:flutter/material.dart';

import '../../../platform/android_platform_service.dart';
import '../../../platform/models/platform_info.dart';
import '../../../platform/models/app_version.dart';
import '../../../platform/models/accessibility_status.dart';
import '../../../platform/models/foreground_app_event.dart';
import '../../../platform/models/device_owner_status.dart';
import '../../auth/password_service.dart';
import '../../auth/password_screen.dart';
import '../../auth/accessibility_password_service.dart';
import '../../auth/accessibility_security_screen.dart';

/// Maximum number of foreground events retained in the debug history.
const int _maxHistorySize = 10;

/// A single entry in the debug foreground event history.
class _HistoryEntry {
  final DateTime timestamp;
  final String packageName;
  final String policyAction;
  final bool policyMatched;

  const _HistoryEntry({
    required this.timestamp,
    required this.packageName,
    required this.policyAction,
    required this.policyMatched,
  });

  String get timeString {
    final h = timestamp.hour.toString().padLeft(2, '0');
    final m = timestamp.minute.toString().padLeft(2, '0');
    final s = timestamp.second.toString().padLeft(2, '0');
    return '$h:$m:$s';
  }
}

/// Settings screen - application configuration.
/// This screen will contain:
/// - General settings (notifications, theme)
/// - Accessibility service status
/// - Privacy settings
/// - About section
/// - Reset options
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  // Authentication state — starts as NOT authenticated.
  // The password screen MUST appear before any settings content is accessible.
  bool _isAuthenticated = false;

  // Native platform data — loaded from Kotlin via MethodChannel.
  PlatformInfo? _platformInfo;
  AppVersion? _appVersion;
  AccessibilityStatus? _accessibilityStatus;
  bool _isLoading = false;
  String? _errorMessage;

  // Foreground app detection — streamed from the AccessibilityService.
  String? _foregroundPackage;
  String? _foregroundPolicyAction;
  bool _foregroundPolicyMatched = false;
  StreamSubscription<ForegroundAppEvent>? _foregroundSubscription;

  // TEMPORARY debug history — in-memory only, lost on app restart.
  final List<_HistoryEntry> _eventHistory = [];

  // Device Owner / Admin status — loaded from DevicePolicyManager.
  DeviceOwnerStatus? _deviceOwnerStatus;

  // Device Owner Protection state — only meaningful when isDeviceOwner == true.
  bool _isUninstallBlocked = false;
  Map<String, dynamic>? _chromePolicy;


  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // _isAuthenticated starts as false — password screen will always appear.
    // It becomes true ONLY after correct password entry via onVerified callback.
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _foregroundSubscription?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // When the user returns from the Android Device Admin activation/deactivation
    // screen, refresh the admin status so the UI reflects the real state.
    if (state == AppLifecycleState.resumed && _isAuthenticated) {
      _loadDeviceOwnerStatus();
    }
  }

  /// Subscribes to foreground app change events from the AccessibilityService.
  void _subscribeToForegroundEvents() {
    final stream = AndroidPlatformService.foregroundAppStream;
    if (stream == null) return;

    _foregroundSubscription = stream.listen(
      (event) {
        if (!mounted) return;
        // TEMPORARY debug logging — package names only, no sensitive data.
        debugPrint('[ForegroundEvent] Received: ${event.packageName}');
        setState(() {
          _foregroundPackage = event.packageName;
          _foregroundPolicyAction = event.policyAction;
          _foregroundPolicyMatched = event.policyMatched;
          // FIFO: prepend new event, drop oldest if over limit.
          _eventHistory.insert(
            0,
            _HistoryEntry(
              timestamp: DateTime.now(),
              packageName: event.packageName,
              policyAction: event.policyAction,
              policyMatched: event.policyMatched,
            ),
          );
          if (_eventHistory.length > _maxHistorySize) {
            _eventHistory.removeLast();
          }
        });
      },
      onError: (error) {
        // Stream errors are non-fatal — the service continues running.
      },
    );
  }

  /// Clears the temporary debug event history.
  void _clearEventHistory() {
    setState(() {
      _eventHistory.clear();
    });
  }

  /// Loads Device Owner / Admin status from Android's DevicePolicyManager.
  Future<void> _loadDeviceOwnerStatus() async {
    try {
      final status = await AndroidPlatformService.getDeviceOwnerStatus();
      if (!mounted) return;
      setState(() {
        _deviceOwnerStatus = status;
      });
      // If Device Owner is active, load protection state
      if (status?.isDeviceOwner == true) {
        _loadDeviceOwnerProtectionState();
      }
    } catch (e) {
      debugPrint('[SettingsScreen] Failed to load device owner status: $e');
    }
  }

  /// Loads the current Device Owner Protection state (uninstall blocking, Chrome policy).
  /// Only called when Device Owner is confirmed active.
  Future<void> _loadDeviceOwnerProtectionState() async {
    try {
      final isBlocked = await AndroidPlatformService.checkUninstallProtection();
      final chromePolicy = await AndroidPlatformService.getChromePolicy();
      if (!mounted) return;
      setState(() {
        _isUninstallBlocked = isBlocked;
        _chromePolicy = chromePolicy;
      });
    } catch (e) {
      debugPrint('[SettingsScreen] Failed to load device owner protection state: $e');
    }
  }

  /// Opens the Accessibility Security password screen.
  /// If the user enters the correct accessibility password, the system
  /// Accessibility Settings are opened. This uses a completely separate
  /// credential from the app password.
  void _openAccessibilitySettingsWithAuth() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (context) => AccessibilitySecurityScreen(
          onVerified: () async {
            // Pop the security screen first, then open system settings.
            Navigator.of(context).pop();
            await AndroidPlatformService.openAccessibilitySettings();
          },
        ),
      ),
    );
  }

  /// Shows a password verification dialog. Returns true if password is correct.
  Future<bool> _verifyPasswordDialog() async {
    final passwordController = TextEditingController();
    bool verified = false;

    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Password Required'),
        content: TextField(
          controller: passwordController,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: 'Enter password',
            border: OutlineInputBorder(),
          ),
          autofocus: true,
          onSubmitted: (_) async {
            if (PasswordService.verify(passwordController.text)) {
              verified = true;
              Navigator.of(dialogContext).pop();
            } else {
              ScaffoldMessenger.of(dialogContext).showSnackBar(
                const SnackBar(content: Text('Incorrect password')),
              );
            }
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () async {
              if (PasswordService.verify(passwordController.text)) {
                verified = true;
                Navigator.of(dialogContext).pop();
              } else {
                ScaffoldMessenger.of(dialogContext).showSnackBar(
                  const SnackBar(content: Text('Incorrect password')),
                );
              }
            },
            child: const Text('Verify'),
          ),
        ],
      ),
    );

    passwordController.dispose();
    return verified;
  }

  /// Fetches all native platform information from the Kotlin layer.
  Future<void> _loadNativeInfo() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final results = await Future.wait([
        AndroidPlatformService.getPlatformInfo(),
        AndroidPlatformService.getAppVersion(),
        AndroidPlatformService.checkAccessibilityStatus(),
      ]);

      if (!mounted) return;

      setState(() {
        _platformInfo = results[0] as PlatformInfo?;
        _appVersion = results[1] as AppVersion?;
        _accessibilityStatus = results[2] as AccessibilityStatus?;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = 'Failed to load native information.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // ── Authentication gate ──────────────────────────────────────────────
    // Password screen MUST appear before any settings content is shown.
    if (!_isAuthenticated) {
      return PasswordScreen(
        onVerified: () {
          setState(() {
            _isAuthenticated = true;
          });
          _loadNativeInfo();
          _loadDeviceOwnerStatus();
          _subscribeToForegroundEvents();
        },
      );
    }
    // ── End authentication gate ──────────────────────────────────────────

    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16.0),
          children: [
            // -----------------------------------------------------------------
            // General section
            // -----------------------------------------------------------------
            Text(
              'General',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            Card(
              child: Column(
                children: [
                  SwitchListTile(
                    title: const Text('Enable Notifications'),
                    subtitle: const Text('Receive intervention alerts'),
                    value: true,
                    onChanged: (value) {
                      // TODO: Toggle notifications
                    },
                  ),
                  const Divider(height: 1),
                  SwitchListTile(
                    title: const Text('Dark Mode'),
                    subtitle: const Text('Use dark theme'),
                    value: false,
                    onChanged: (value) {
                      // TODO: Toggle theme
                    },
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // Services section
            // -----------------------------------------------------------------
            Text(
              'Services',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            Card(
              child: Column(
                children: [
                  ListTile(
                    leading: const Icon(Icons.accessibility_new),
                    title: const Text('Accessibility Service'),
                    subtitle: Text(
                      _accessibilityStatus?.isEnabled == true
                          ? 'Enabled'
                          : 'Not enabled',
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: _openAccessibilitySettingsWithAuth,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // Native Platform Status (temporary — for bridge verification)
            // -----------------------------------------------------------------
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Native Platform Status',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  onPressed: _isLoading ? null : _loadNativeInfo,
                  tooltip: 'Refresh',
                ),
              ],
            ),
            const SizedBox(height: 8),
            _buildNativeStatusCard(),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // Device Administrator status
            // -----------------------------------------------------------------
            Text(
              'Device Administrator',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            _buildDeviceProtectionCard(),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // Device Owner Protection (only meaningful when Device Owner is active)
            // -----------------------------------------------------------------
            Text(
              'Device Owner Protection',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            _buildDeviceOwnerProtectionCard(),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // TEMPORARY Debug: Policy Engine Status
            // -----------------------------------------------------------------
            _buildPolicyEngineStatus(),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // TEMPORARY Debug: Recent Foreground Events
            // -----------------------------------------------------------------
            _buildDebugEventHistory(),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // Privacy section
            // -----------------------------------------------------------------
            Text(
              'Privacy',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            Card(
              child: Column(
                children: [
                  ListTile(
                    leading: const Icon(Icons.storage),
                    title: const Text('Local Data'),
                    subtitle: const Text('All data stored locally'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () {
                      // TODO: Show data info
                    },
                  ),
                  const Divider(height: 1),
                  ListTile(
                    leading: const Icon(Icons.delete_outline),
                    title: const Text('Clear All Data'),
                    subtitle: const Text('Reset application'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () {
                      // TODO: Show confirmation dialog
                    },
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // -----------------------------------------------------------------
            // About section
            // -----------------------------------------------------------------
            Text(
              'About',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
            ),
            const SizedBox(height: 8),
            Card(
              child: Column(
                children: [
                  ListTile(
                    leading: const Icon(Icons.info_outline),
                    title: const Text('Version'),
                    subtitle: Text(
                      _appVersion != null
                          ? '${_appVersion!.versionName} (${_appVersion!.versionCode})'
                          : 'Loading...',
                    ),
                  ),
                  const Divider(height: 1),
                  ListTile(
                    leading: const Icon(Icons.code),
                    title: const Text('Open Source Licenses'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () {
                      showLicensePage(
                        context: context,
                        applicationName: 'AI Guardian',
                      );
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Device Administrator status card builder
  // ---------------------------------------------------------------------------

  Widget _buildDeviceProtectionCard() {
    final status = _deviceOwnerStatus;

    if (status == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: const [
              SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(width: 12),
              Text('Loading device admin status...'),
            ],
          ),
        ),
      );
    }

    final isAdmin = status.isAdminActive;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Device Administrator status
            _NativeInfoRow(
              icon: Icons.admin_panel_settings,
              label: 'Device Administrator',
              value: isAdmin ? 'Active' : 'Not Active',
              valueColor: isAdmin
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 12),

            // Device Owner status (informational — not activatable from UI)
            _NativeInfoRow(
              icon: Icons.shield,
              label: 'Device Owner',
              value: status.isDeviceOwner ? 'Active' : 'Not Provisioned',
              valueColor: status.isDeviceOwner
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(height: 16),

            // Activation / deactivation button
            if (!isAdmin)
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () async {
                    try {
                      final result = await AndroidPlatformService.requestDeviceAdmin();
                      if (result != null && result['launched'] == true) {
                        // Status will refresh automatically via didChangeAppLifecycleState
                      } else if (result != null && result['alreadyActive'] == true) {
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Device Administrator is already active.')),
                          );
                        }
                      }
                    } catch (e) {
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('Failed to enable Device Administrator: $e')),
                        );
                      }
                    }
                  },
                  icon: const Icon(Icons.add_moderator),
                  label: const Text('Enable Device Administrator'),
                ),
              )
            else
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () async {
                    try {
                      final result = await AndroidPlatformService.removeDeviceAdmin();
                      if (result != null && result['launched'] == true) {
                        // Status will refresh automatically via didChangeAppLifecycleState
                      } else if (result != null && result['notActive'] == true) {
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Device Administrator is not active.')),
                          );
                        }
                      }
                    } catch (e) {
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('Failed to disable Device Administrator: $e')),
                        );
                      }
                    }
                  },
                  icon: const Icon(Icons.remove_moderator),
                  label: const Text('Disable Device Administrator'),
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Device Owner Protection card builder
  // ---------------------------------------------------------------------------

  Widget _buildDeviceOwnerProtectionCard() {
    final status = _deviceOwnerStatus;

    if (status == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: const [
              SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(width: 12),
              Text('Loading device owner status...'),
            ],
          ),
        ),
      );
    }

    final isOwner = status.isDeviceOwner;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Device Owner status
            _NativeInfoRow(
              icon: Icons.shield,
              label: 'Device Owner',
              value: isOwner ? 'Active' : 'Not Active',
              valueColor: isOwner
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 8),

            if (!isOwner)
              Padding(
                padding: const EdgeInsets.only(top: 8.0),
                child: Text(
                  'Device Owner protections require Device Owner provisioning on a fresh device. '
                  'These features are unavailable until Device Owner is active.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              )
            else ...[
              const Divider(height: 24),

              // 1. Protect AI Guardian from uninstall
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                secondary: const Icon(Icons.shield),
                title: const Text('Protect AI Guardian'),
                subtitle: Text(
                  _isUninstallBlocked
                      ? 'Uninstall is blocked'
                      : 'Uninstall is allowed',
                ),
                value: _isUninstallBlocked,
                onChanged: (value) async {
                  // Require password before changing
                  if (!await _verifyPasswordDialog()) return;

                  try {
                    if (value) {
                      await AndroidPlatformService.applyUninstallProtection();
                    } else {
                      await AndroidPlatformService.removeUninstallProtection();
                    }
                    // Refresh state
                    _loadDeviceOwnerProtectionState();
                  } catch (e) {
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text('Failed: $e')),
                      );
                    }
                  }
                },
              ),
              const Divider(height: 1),

              // 2. Block uninstall of selected apps (placeholder)
              ListTile(
                leading: const Icon(Icons.app_blocking),
                title: const Text('Block App Uninstall'),
                subtitle: const Text('Future capability — requires app discovery'),
                trailing: const Icon(Icons.chevron_right),
                enabled: false,
                onTap: () {
                  // Future: show app picker for uninstall blocking
                },
              ),
              const Divider(height: 1),

              // 3. Chrome / application restriction policy
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                secondary: const Icon(Icons.language),
                title: const Text('Chrome URL Restrictions'),
                subtitle: Text(
                  _chromePolicy != null
                      ? 'Policy active (${_chromePolicy!.length} entries)'
                      : 'No policy active',
                ),
                value: _chromePolicy != null,
                onChanged: (value) async {
                  // Require password before changing
                  if (!await _verifyPasswordDialog()) return;

                  try {
                    if (value) {
                      // Apply with empty list — user can configure domains later
                      await AndroidPlatformService.applyChromeBlocklist([]);
                    } else {
                      await AndroidPlatformService.clearChromePolicy();
                    }
                    // Refresh state
                    _loadDeviceOwnerProtectionState();
                  } catch (e) {
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text('Failed: $e')),
                      );
                    }
                  }
                },
              ),
            ],
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Native status card builder
  // ---------------------------------------------------------------------------

  Widget _buildNativeStatusCard() {
    if (_isLoading) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(24.0),
          child: Center(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                SizedBox(width: 12),
                Text('Loading native information...'),
              ],
            ),
          ),
        ),
      );
    }

    if (_errorMessage != null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            children: [
              Icon(
                Icons.error_outline,
                color: Theme.of(context).colorScheme.error,
                size: 32,
              ),
              const SizedBox(height: 8),
              Text(
                _errorMessage!,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              FilledButton.tonal(
                onPressed: _loadNativeInfo,
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _NativeInfoRow(
              icon: Icons.phone_android,
              label: 'Platform',
              value: _platformInfo?.platform ?? '—',
            ),
            const SizedBox(height: 12),
            _NativeInfoRow(
              icon: Icons.code,
              label: 'Android SDK Version',
              value: _platformInfo?.sdkVersion != null &&
                      _platformInfo!.sdkVersion > 0
                  ? '${_platformInfo!.sdkVersion}'
                  : '—',
            ),
            const SizedBox(height: 12),
            _NativeInfoRow(
              icon: Icons.business,
              label: 'Device Manufacturer',
              value: _platformInfo?.manufacturer ?? '—',
            ),
            const SizedBox(height: 12),
            _NativeInfoRow(
              icon: Icons.devices,
              label: 'Device Model',
              value: _platformInfo?.model ?? '—',
            ),
            const Divider(height: 24),
            _NativeInfoRow(
              icon: Icons.tag,
              label: 'App Version',
              value: _appVersion != null
                  ? '${_appVersion!.versionName} (${_appVersion!.versionCode})'
                  : '—',
            ),
            const SizedBox(height: 12),
            _NativeInfoRow(
              icon: Icons.accessibility_new,
              label: 'Accessibility Service',
              value: _accessibilityStatus?.isEnabled == true
                  ? 'Enabled'
                  : 'Not enabled',
              valueColor: _accessibilityStatus?.isEnabled == true
                  ? Theme.of(context).colorScheme.primary
                  : null,
            ),
            const SizedBox(height: 12),
            _NativeInfoRow(
              icon: Icons.app_registration,
              label: 'Foreground App',
              value: _foregroundPackage ?? 'Not detected',
              valueColor: _foregroundPackage != null
                  ? Theme.of(context).colorScheme.tertiary
                  : null,
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: _openAccessibilitySettingsWithAuth,
                icon: const Icon(Icons.settings),
                label: const Text('Open Accessibility Settings'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // TEMPORARY debug policy engine status builder
  // ---------------------------------------------------------------------------

  Widget _buildPolicyEngineStatus() {
    final isBlock = _foregroundPolicyAction == 'BLOCK';
    final actionColor = isBlock
        ? Theme.of(context).colorScheme.error
        : Theme.of(context).colorScheme.primary;
    final actionIcon = isBlock ? Icons.block : Icons.check_circle;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.security,
                  size: 20,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Policy Engine Status',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: Theme.of(context)
                        .colorScheme
                        .primaryContainer
                        .withValues(alpha: 0.3),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    'DEBUG',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                          color: Theme.of(context).colorScheme.primary,
                          fontWeight: FontWeight.w700,
                        ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Evaluation only — no enforcement',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 16),
            if (_foregroundPackage == null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: Center(
                  child: Text(
                    'No foreground app detected yet',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color:
                              Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ),
              )
            else ...[
              _NativeInfoRow(
                icon: Icons.app_registration,
                label: 'Current App',
                value: _foregroundPackage!,
                valueColor: Theme.of(context).colorScheme.tertiary,
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Icon(
                    actionIcon,
                    size: 20,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Policy',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: Theme.of(context)
                                .colorScheme
                                .onSurfaceVariant,
                          ),
                    ),
                  ),
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: actionColor.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      _foregroundPolicyAction!,
                      style:
                          Theme.of(context).textTheme.bodyMedium?.copyWith(
                                fontWeight: FontWeight.w700,
                                color: actionColor,
                              ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              _NativeInfoRow(
                icon: Icons.rule,
                label: 'Policy Matched',
                value: _foregroundPolicyMatched ? 'Yes' : 'No',
                valueColor: _foregroundPolicyMatched
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ],
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // TEMPORARY debug event history builder
  // ---------------------------------------------------------------------------

  Widget _buildDebugEventHistory() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.bug_report_outlined,
                  size: 20,
                  color: Theme.of(context).colorScheme.error,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Recent Foreground Events',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ),
                if (_eventHistory.isNotEmpty)
                  TextButton(
                    onPressed: _clearEventHistory,
                    child: const Text('Clear'),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Debug — shows last $_maxHistorySize events (in-memory only)',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 12),
            if (_eventHistory.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16.0),
                child: Center(
                  child: Text(
                    'No events yet — switch between apps to see activity',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ),
              )
            else
              ...List.generate(_eventHistory.length, (index) {
                final entry = _eventHistory[index];
                final isBlock = entry.policyAction == 'BLOCK';
                final actionColor = isBlock
                    ? Theme.of(context).colorScheme.error
                    : Theme.of(context).colorScheme.primary;
                return Padding(
                  padding: const EdgeInsets.only(bottom: 8.0),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${_eventHistory.length - index}.',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color:
                                  Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        entry.timeString,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                              fontFamily: 'monospace',
                            ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          entry.packageName,
                          style:
                              Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    fontWeight: FontWeight.w600,
                                  ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: actionColor.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          entry.policyAction,
                          style:
                              Theme.of(context).textTheme.labelSmall?.copyWith(
                                    color: actionColor,
                                    fontWeight: FontWeight.w700,
                                  ),
                        ),
                      ),
                    ],
                  ),
                );
              }),
          ],
        ),
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// Native info row widget
// -----------------------------------------------------------------------------

class _NativeInfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color? valueColor;

  const _NativeInfoRow({
    required this.icon,
    required this.label,
    required this.value,
    this.valueColor,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          icon,
          size: 20,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            label,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ),
        Flexible(
          child: Text(
            value,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                  color: valueColor,
                ),
            textAlign: TextAlign.end,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}
