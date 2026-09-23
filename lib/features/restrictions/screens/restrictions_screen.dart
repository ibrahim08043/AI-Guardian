import 'dart:io';
import 'package:flutter/material.dart';

import '../../../platform/android_platform_service.dart';
import '../../../platform/models/installed_app.dart';
import '../../../platform/models/policy_model.dart';
import '../../auth/password_service.dart';
import '../../auth/password_screen.dart';
import 'restriction_settings_screen.dart';
import 'domain_restrictions_screen.dart';
import 'content_filter_screen.dart';

/// Restrictions screen - manage app blocking rules and domain restrictions.
/// Uses tabs to switch between app restrictions and website/domain restrictions.
///
/// Authentication is embedded directly in this screen to ensure the password
/// gate is enforced regardless of routing behavior.
class RestrictionsScreen extends StatefulWidget {
  const RestrictionsScreen({super.key});

  @override
  State<RestrictionsScreen> createState() => _RestrictionsScreenState();
}

class _RestrictionsScreenState extends State<RestrictionsScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;

  // Authentication state — starts as NOT authenticated.
  // The password screen MUST appear before any restriction content is accessible.
  bool _isAuthenticated = false;

  List<InstalledApp> installedApps = [];
  bool isLoading = true;
  String? errorMessage;

  // Cache of policies for status display
  Map<String, PolicyModel> _policyCache = {};

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    // _isAuthenticated starts as false — password screen will always appear.
    // It becomes true ONLY after correct password entry via onVerified callback.
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Refresh policies when returning from settings screen
    _refreshPolicies();
  }

  Future<void> _refreshPolicies() async {
    try {
      final policies = await AndroidPlatformService.getPolicies();
      if (!mounted) return;
      setState(() {
        _policyCache = {
          for (var p in policies) p.packageName: p,
        };
      });
    } catch (_) {
      // Silently fail — UI will show defaults
    }
  }

  Future<void> _loadInstalledApps() async {
    try {
      setState(() {
        isLoading = true;
        errorMessage = null;
      });

      debugPrint('[RestrictionsScreen] Starting to load installed apps');

      // Get installed apps from Android
      final rawApps = await AndroidPlatformService.getInstalledApps();
      debugPrint(
          '[RestrictionsScreen] Received ${rawApps.length} raw apps from platform service');

      if (rawApps.isEmpty) {
        // Fallback: Show error message for debugging
        setState(() {
          errorMessage =
              'No apps returned from native layer. Check logcat for AIGuardianPlatform logs.';
          isLoading = false;
        });
        return;
      }

      // Convert to InstalledApp models
      final apps = rawApps.map((map) => InstalledApp.fromMap(map)).toList();
      debugPrint(
          '[RestrictionsScreen] Converted to ${apps.length} InstalledApp models');

      // Load all policies for status display
      final policies = await AndroidPlatformService.getPolicies();
      final policyMap = {
        for (var p in policies) p.packageName: p,
      };

      // For each app, populate isBlocked from policy cache
      for (var app in apps) {
        final policy = policyMap[app.packageName];
        if (policy != null && policy.enabled) {
          app.setBlocked(policy.action == PolicyActionModel.block);
        }
      }

      debugPrint(
          '[RestrictionsScreen] About to set state with ${apps.length} apps');
      if (mounted) {
        setState(() {
          installedApps = apps;
          _policyCache = policyMap;
          isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('[RestrictionsScreen] Error loading apps: $e');
      if (mounted) {
        setState(() {
          errorMessage = 'Error: $e';
          isLoading = false;
        });
      }
    }
  }

  void _openSettings(InstalledApp app) async {
    // Navigate to restriction settings
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => RestrictionSettingsScreen(
          packageName: app.packageName,
          appName: app.appName,
          iconPath: app.iconPath,
        ),
      ),
    );

    // Refresh policies when returning
    await _refreshPolicies();
    _loadInstalledApps();
  }

  @override
  Widget build(BuildContext context) {
    // ── Authentication gate ──────────────────────────────────────────────
    // Password screen MUST appear before any restriction content is shown.
    if (!_isAuthenticated) {
      return PasswordScreen(
        onVerified: () {
          setState(() {
            _isAuthenticated = true;
          });
          _loadInstalledApps();
        },
      );
    }
    // ── End authentication gate ──────────────────────────────────────────

    if (isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Restrictions')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    if (errorMessage != null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Restrictions')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Text('Error loading apps'),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: _loadInstalledApps,
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    if (installedApps.isEmpty) {
      return Scaffold(
        appBar: AppBar(title: const Text('Restrictions')),
        body: const Center(
          child: Text('No apps found'),
        ),
      );
    }

    // Separate restricted and unrestricted apps
    final restrictedApps = installedApps.where((a) => a.isBlocked).toList();
    final unrestrictedApps = installedApps.where((a) => !a.isBlocked).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Restrictions'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Apps'),
            Tab(text: 'Websites'),
            Tab(text: 'Words'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          // Apps tab
          ListView(
            children: [
              // Restricted apps section
              if (restrictedApps.isNotEmpty) ...[
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                  child: Text(
                    'RESTRICTED (${restrictedApps.length})',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                ),
                ...restrictedApps.map((app) => _RestrictedAppTile(
                      app: app,
                      policy: _policyCache[app.packageName],
                      onTap: () => _openSettings(app),
                    )),
                const Divider(height: 1),
              ],

              // Unrestricted apps section
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                child: Text(
                  'ALL APPS (${unrestrictedApps.length})',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Theme.of(context)
                        .colorScheme
                        .onSurface
                        .withValues(alpha: 0.6),
                  ),
                ),
              ),
              ...unrestrictedApps.map((app) => _RestrictedAppTile(
                    app: app,
                    policy: _policyCache[app.packageName],
                    onTap: () => _openSettings(app),
                  )),
            ],
          ),
          // Websites tab
          const DomainRestrictionsScreen(),
          // Words tab — content filtering
          const ContentFilterScreen(),
        ],
      ),
    );
  }
}

class _RestrictedAppTile extends StatelessWidget {
  final InstalledApp app;
  final PolicyModel? policy;
  final VoidCallback? onTap;

  const _RestrictedAppTile({
    required this.app,
    this.policy,
    this.onTap,
  });

  String _getStatusText() {
    if (!app.isBlocked) return 'Allowed';
    final activeSchedules = policy?.schedules.where((s) => s.enabled).toList() ?? [];
    if (activeSchedules.isNotEmpty) {
      return 'Blocked · ${activeSchedules.length} schedule${activeSchedules.length == 1 ? '' : 's'}';
    }
    if (policy?.dailyLimit.enabled == true) {
      return 'Blocked · ${policy?.dailyLimit.displayString() ?? ''}';
    }
    return 'Blocked';
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: ListTile(
        leading: app.iconPath != null && app.iconPath!.isNotEmpty
            ? SizedBox(
                width: 40,
                height: 40,
                child: Image.file(
                  File(app.iconPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (context, error, stackTrace) {
                    return Icon(
                      Icons.app_blocking,
                      color: Theme.of(context).colorScheme.primary,
                    );
                  },
                ),
              )
            : Icon(
                Icons.app_blocking,
                color: Theme.of(context).colorScheme.primary,
              ),
        title: Text(app.appName),
        subtitle: Text(
          _getStatusText(),
          style: TextStyle(
            fontSize: 12,
            color: app.isBlocked ? Colors.red : Colors.green,
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (app.isBlocked)
              Icon(Icons.block, color: Colors.red.shade400, size: 20)
            else
              Icon(Icons.check_circle, color: Colors.green.shade400, size: 20),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right),
          ],
        ),
        onTap: onTap,
      ),
    );
  }
}
