import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'platform_channels.dart';
import 'models/platform_info.dart';
import 'models/app_version.dart';
import 'models/accessibility_status.dart';
import 'models/foreground_app_event.dart';
import 'models/policy_model.dart';
import 'models/device_owner_status.dart';

/// Centralized service for all Flutter <-> Kotlin platform communication.
///
/// Feature screens should use this service instead of calling
/// PlatformChannels.methodChannel directly. This ensures:
/// - Consistent error handling across the app
/// - Single place to add logging or retry logic
/// - Typed return values instead of raw maps
///
/// All methods return null on failure instead of crashing.
class AndroidPlatformService {
  AndroidPlatformService._();

  // ---------------------------------------------------------------------------
  // Platform info
  // ---------------------------------------------------------------------------

  /// Returns Android platform information from the native layer.
  /// Returns null if the call fails or the response is invalid.
  static Future<PlatformInfo?> getPlatformInfo() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('getPlatformInfo');
      if (result == null) return null;
      return PlatformInfo.fromMap(result);
    } on PlatformException catch (e) {
      _logError('getPlatformInfo', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getPlatformInfo', e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Accessibility status
  // ---------------------------------------------------------------------------

  /// Checks whether AI Guardian's AccessibilityService is enabled
  /// in Android Settings → Accessibility. The user must manually enable it.
  static Future<AccessibilityStatus?> checkAccessibilityStatus() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('checkAccessibilityStatus');
      if (result == null) return null;
      return AccessibilityStatus.fromMap(result);
    } on PlatformException catch (e) {
      _logError('checkAccessibilityStatus', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('checkAccessibilityStatus', e);
      return null;
    }
  }

  /// Opens the Android Accessibility Settings page via a native Intent.
  /// Returns true if the intent was launched successfully.
  static Future<bool> openAccessibilitySettings() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMethod<bool>('openAccessibilitySettings');
      return result ?? false;
    } on PlatformException catch (e) {
      _logError('openAccessibilitySettings', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('openAccessibilitySettings', e);
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // App version
  // ---------------------------------------------------------------------------

  /// Returns the app version from the native Android PackageInfo.
  static Future<AppVersion?> getAppVersion() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('getAppVersion');
      if (result == null) return null;
      return AppVersion.fromMap(result);
    } on PlatformException catch (e) {
      _logError('getAppVersion', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getAppVersion', e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Foreground app event stream
  // ---------------------------------------------------------------------------

  /// Stream of foreground app change events from the native AccessibilityService.
  ///
  /// Each event contains the package name of the newly foregrounded app.
  /// Events are deduplicated on the native side — the same package appearing
  /// consecutively will not produce multiple events.
  ///
  /// Returns null if the EventChannel is not available.
  static Stream<ForegroundAppEvent>? get foregroundAppStream {
    try {
      return PlatformChannels.eventChannel
          .receiveBroadcastStream()
          .where((event) => event is Map)
          .cast<Map<dynamic, dynamic>>()
          .expand((map) {
        try {
          return [ForegroundAppEvent.fromMap(map)];
        } catch (_) {
          // Malformed event — skip silently.
          return <ForegroundAppEvent>[];
        }
      });
    } on MissingPluginException {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  static void _logError(String method, Object error) {
    // In production this could integrate with a logging framework.
    // For now we use assert so it only fires in debug mode.
    assert(false, '[PlatformService] $method failed: $error');
  }

  // ---------------------------------------------------------------------------
  // Policy management
  // ---------------------------------------------------------------------------

  /// Get all policies from persistent storage.
  /// Returns an empty list if no policies are found or if the call fails.
  static Future<List<PolicyModel>> getPolicies() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeListMethod<Map<dynamic, dynamic>>('getPolicies');
      if (result == null) return [];

      return result
          .map((map) => PolicyModel.fromBasicMap(map))
          .whereType<PolicyModel>()
          .toList();
    } on PlatformException catch (e) {
      _logError('getPolicies', e);
      return [];
    } on MissingPluginException catch (e) {
      _logError('getPolicies', e);
      return [];
    }
  }

  /// Get a specific policy by package name.
  /// Returns null if not found or if the call fails.
  static Future<PolicyModel?> getPolicy(String packageName) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>(
        'getPolicy',
        {'packageName': packageName},
      );
      if (result == null) return null;
      return PolicyModel.fromBasicMap(result);
    } on PlatformException catch (e) {
      _logError('getPolicy', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getPolicy', e);
      return null;
    }
  }

  /// Get full policy with all Phase C fields (schedule, daily limit).
  static Future<PolicyModel?> getFullPolicy(String packageName) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>(
        'getFullPolicy',
        {'packageName': packageName},
      );
      if (result == null) return null;
      return PolicyModel.fromMap(result);
    } on PlatformException catch (e) {
      _logError('getFullPolicy', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getFullPolicy', e);
      return null;
    }
  }

  /// Save a new policy to persistent storage.
  /// Returns true if successful, false otherwise.
  static Future<bool> savePolicy(PolicyModel policy) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'savePolicy',
        {
          'packageName': policy.packageName,
          'action': policy.action.toNative(),
          'enabled': policy.enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('savePolicy', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('savePolicy', e);
      return false;
    }
  }

  /// Update an existing policy in persistent storage.
  /// Returns true if successful, false otherwise.
  static Future<bool> updatePolicy(PolicyModel policy) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'updatePolicy',
        {
          'packageName': policy.packageName,
          'action': policy.action.toNative(),
          'enabled': policy.enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('updatePolicy', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('updatePolicy', e);
      return false;
    }
  }

  /// Delete a policy from persistent storage.
  /// Returns true if successful, false otherwise.
  static Future<bool> deletePolicy(String packageName) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'deletePolicy',
        {'packageName': packageName},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('deletePolicy', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('deletePolicy', e);
      return false;
    }
  }

  /// Enable or disable a policy.
  /// Returns true if successful, false otherwise.
  static Future<bool> setPolicyEnabled(
    String packageName,
    bool enabled,
  ) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'setPolicyEnabled',
        {
          'packageName': packageName,
          'enabled': enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('setPolicyEnabled', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('setPolicyEnabled', e);
      return false;
    }
  }

  /// Get list of installed user applications.
  /// Returns a list of maps containing packageName, appName, and iconPath.
  /// Returns an empty list if the call fails.
  static Future<List<Map<String, dynamic>>> getInstalledApps() async {
    try {
      debugPrint('[AndroidPlatformService] Calling getInstalledApps on MethodChannel');
      final result = await PlatformChannels.methodChannel
          .invokeListMethod<Map<dynamic, dynamic>>('getInstalledApps');

      debugPrint('[AndroidPlatformService] getInstalledApps returned: $result');

      if (result == null) {
        debugPrint('[AndroidPlatformService] Result is null, returning empty list');
        return [];
      }

      debugPrint('[AndroidPlatformService] Result has ${result.length} items');
      final converted = result
          .map((map) => map.cast<String, dynamic>())
          .toList();
      debugPrint('[AndroidPlatformService] Converted to ${converted.length} maps');
      return converted;
    } on PlatformException catch (e) {
      debugPrint('[AndroidPlatformService] PlatformException: ${e.code} - ${e.message}');
      _logError('getInstalledApps', e);
      return [];
    } on MissingPluginException catch (e) {
      debugPrint('[AndroidPlatformService] MissingPluginException: $e');
      _logError('getInstalledApps', e);
      return [];
    } catch (e) {
      debugPrint('[AndroidPlatformService] Unexpected error: $e');
      return [];
    }
  }

  // ---------------------------------------------------------------------------
  // Phase C: Smart restriction methods
  // ---------------------------------------------------------------------------

  /// Save a schedule configuration for a policy.
  /// Returns true if successful, false otherwise.
  static Future<bool> saveSchedule({
    required String packageName,
    required bool enabled,
    int? startMinutes,
    int? endMinutes,
  }) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'saveSchedule',
        {
          'packageName': packageName,
          'enabled': enabled,
          'startMinutes': startMinutes,
          'endMinutes': endMinutes,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('saveSchedule', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('saveSchedule', e);
      return false;
    }
  }

  /// Save a daily limit configuration for a policy.
  /// Returns true if successful, false otherwise.
  static Future<bool> saveDailyLimit({
    required String packageName,
    required bool enabled,
    int? limitMinutes,
  }) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'saveDailyLimit',
        {
          'packageName': packageName,
          'enabled': enabled,
          'limitMinutes': limitMinutes,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('saveDailyLimit', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('saveDailyLimit', e);
      return false;
    }
  }

  /// Get today's usage for a package.
  /// Returns a map with usageMinutes, usageMs, and date.
  static Future<Map<String, dynamic>?> getUsageToday(String packageName) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>(
        'getUsageToday',
        {'packageName': packageName},
      );
      if (result == null) return null;
      return result.cast<String, dynamic>();
    } on PlatformException catch (e) {
      _logError('getUsageToday', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getUsageToday', e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Phase K: Domain blocking methods
  // ---------------------------------------------------------------------------

  /// Get all domain blocking rules.
  static Future<List<Map<String, dynamic>>> getAllDomains() async {
    try {
      debugPrint('[AndroidPlatformService] getAllDomains() calling MethodChannel...');
      final result = await PlatformChannels.methodChannel
          .invokeListMethod<Map<dynamic, dynamic>>('getAllDomains');
      debugPrint('[AndroidPlatformService] getAllDomains() got result: $result');
      if (result == null) return [];
      return result.map((map) => map.cast<String, dynamic>()).toList();
    } on PlatformException catch (e) {
      debugPrint('[AndroidPlatformService] getAllDomains() PlatformException: ${e.code} - ${e.message}');
      _logError('getAllDomains', e);
      return [];
    } on MissingPluginException catch (e) {
      debugPrint('[AndroidPlatformService] getAllDomains() MissingPluginException: $e');
      _logError('getAllDomains', e);
      return [];
    }
  }

  /// Save a domain blocking rule.
  /// Returns true if successful, false otherwise.
  static Future<bool> saveDomain({
    required String domain,
    required bool enabled,
  }) async {
    try {
      debugPrint('[AndroidPlatformService] saveDomain() calling MethodChannel with domain=$domain, enabled=$enabled');
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'saveDomain',
        {
          'domain': domain,
          'enabled': enabled,
        },
      );
      debugPrint('[AndroidPlatformService] saveDomain() got result: $result');
      return result?['success'] == true;
    } on PlatformException catch (e) {
      debugPrint('[AndroidPlatformService] saveDomain() PlatformException: ${e.code} - ${e.message}');
      _logError('saveDomain', e);
      return false;
    } on MissingPluginException catch (e) {
      debugPrint('[AndroidPlatformService] saveDomain() MissingPluginException: $e');
      _logError('saveDomain', e);
      return false;
    }
  }

  /// Delete a domain blocking rule.
  /// Returns true if successful, false otherwise.
  static Future<bool> deleteDomain(String domain) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'deleteDomain',
        {'domain': domain},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('deleteDomain', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('deleteDomain', e);
      return false;
    }
  }

  /// Enable or disable a domain blocking rule.
  /// Returns true if successful, false otherwise.
  static Future<bool> setDomainEnabled(
    String domain,
    bool enabled,
  ) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'setDomainEnabled',
        {
          'domain': domain,
          'enabled': enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('setDomainEnabled', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('setDomainEnabled', e);
      return false;
    }
  }

  /// Start domain blocking via local VPN.
  /// Returns true if successful, false otherwise.
  static Future<bool> startDomainBlocking() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('startDomainBlocking');
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('startDomainBlocking', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('startDomainBlocking', e);
      return false;
    }
  }

  /// Stop domain blocking.
  /// Returns true if successful, false otherwise.
  static Future<bool> stopDomainBlocking() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('stopDomainBlocking');
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('stopDomainBlocking', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('stopDomainBlocking', e);
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Authentication
  // ---------------------------------------------------------------------------

  /// Verifies a password against the stored app password in Android assets.
  /// Returns true if the password matches, false otherwise.
  static Future<bool> verifyPassword(String password) async {
    try {
      final result = await PlatformChannels.methodChannel.invokeMapMethod<
          String,
          dynamic>(
        'verifyPassword',
        {'password': password},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('verifyPassword', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('verifyPassword', e);
      return false;
    }
  }

  /// Checks whether a password is configured in Android assets.
  /// Returns true if a non-empty password exists, false otherwise.
  static Future<bool> hasPassword() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('hasPassword');
      return result?['hasPassword'] == true;
    } on PlatformException catch (e) {
      // Fail-closed: if we can't check, assume password exists.
      _logError('hasPassword', e);
      return true;
    } on MissingPluginException catch (e) {
      _logError('hasPassword', e);
      return true;
    }
  }

  // ---------------------------------------------------------------------------
  // Device Owner / Device Admin status
  // ---------------------------------------------------------------------------

  /// Returns the current Device Owner / Device Admin status from Android's
  /// DevicePolicyManager. All values reflect actual native state.
  static Future<DeviceOwnerStatus?> getDeviceOwnerStatus() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('getDeviceOwnerStatus');
      if (result == null) return null;
      return DeviceOwnerStatus.fromMap(result);
    } on PlatformException catch (e) {
      _logError('getDeviceOwnerStatus', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getDeviceOwnerStatus', e);
      return null;
    }
  }

  /// Launch the Android system Device Administrator activation screen.
  /// Returns a map with `launched` (bool) and `alreadyActive` (bool).
  /// Throws PlatformException if the native layer fails (e.g. no activity to handle the intent).
  static Future<Map<String, dynamic>?> requestDeviceAdmin() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('requestDeviceAdmin');
      if (result == null) return null;
      return result.cast<String, dynamic>();
    } on PlatformException catch (e) {
      // Re-throw so the UI layer can display the error to the user
      rethrow;
    } on MissingPluginException catch (e) {
      _logError('requestDeviceAdmin', e);
      return null;
    }
  }

  /// Launch the Android system Device Administrator deactivation screen.
  /// Returns a map with `launched` (bool) and `notActive` (bool).
  /// Throws PlatformException if the native layer fails.
  static Future<Map<String, dynamic>?> removeDeviceAdmin() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<dynamic, dynamic>('removeDeviceAdmin');
      if (result == null) return null;
      return result.cast<String, dynamic>();
    } on PlatformException catch (e) {
      // Re-throw so the UI layer can display the error to the user
      rethrow;
    } on MissingPluginException catch (e) {
      _logError('removeDeviceAdmin', e);
      return null;
    }
  }

  /// Apply system-level uninstall blocking for AI Guardian itself.
  /// Requires Device Owner status. Returns true if protection was applied.
  static Future<bool> applyUninstallProtection() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('applyUninstallProtection');
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('applyUninstallProtection', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('applyUninstallProtection', e);
      return false;
    }
  }

  /// Remove system-level uninstall blocking for AI Guardian.
  /// Requires Device Owner status. Returns true if protection was removed.
  static Future<bool> removeUninstallProtection() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('removeUninstallProtection');
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('removeUninstallProtection', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('removeUninstallProtection', e);
      return false;
    }
  }

  /// Check if uninstall of AI Guardian is currently blocked by Device Owner policy.
  /// Returns true if blocked, false otherwise.
  static Future<bool> checkUninstallProtection() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('checkUninstallProtection');
      return result?['isUninstallBlocked'] == true;
    } on PlatformException catch (e) {
      _logError('checkUninstallProtection', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('checkUninstallProtection', e);
      return false;
    }
  }

  /// Apply Chrome URL blocklist via Device Owner application restrictions.
  /// Requires Device Owner status. Returns true if applied.
  static Future<bool> applyChromeBlocklist(List<String> domains) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'applyChromeBlocklist',
        {'domains': domains},
      );
      return result?['applied'] == true;
    } on PlatformException catch (e) {
      _logError('applyChromeBlocklist', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('applyChromeBlocklist', e);
      return false;
    }
  }

  /// Get the current Chrome application restriction policy.
  /// Returns null if no policy is set or if not Device Owner.
  static Future<Map<String, dynamic>?> getChromePolicy() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('getChromePolicy');
      if (result == null) return null;
      return result.cast<String, dynamic>();
    } on PlatformException catch (e) {
      _logError('getChromePolicy', e);
      return null;
    } on MissingPluginException catch (e) {
      _logError('getChromePolicy', e);
      return null;
    }
  }

  /// Clear Chrome application restriction policy.
  /// Requires Device Owner status. Returns true if cleared.
  static Future<bool> clearChromePolicy() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('clearChromePolicy');
      return result?['cleared'] == true;
    } on PlatformException catch (e) {
      _logError('clearChromePolicy', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('clearChromePolicy', e);
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 2B: Content filter methods
  // ---------------------------------------------------------------------------

  /// Get all content filter rules from persistent storage.
  static Future<List<Map<String, dynamic>>> getAllContentFilterRules() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeListMethod<Map<dynamic, dynamic>>('getAllContentFilterRules');
      if (result == null) return [];
      return result.map((map) => map.cast<String, dynamic>()).toList();
    } on PlatformException catch (e) {
      _logError('getAllContentFilterRules', e);
      return [];
    } on MissingPluginException catch (e) {
      _logError('getAllContentFilterRules', e);
      return [];
    }
  }

  /// Save a new content filter rule.
  /// Returns the inserted rule ID, or -1 on failure.
  static Future<int> saveContentFilterRule({
    required String phrase,
    String matchMode = 'CONTAINS',
    bool enabled = true,
  }) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'saveContentFilterRule',
        {
          'phrase': phrase,
          'matchMode': matchMode,
          'enabled': enabled,
        },
      );
      return (result?['id'] as int?) ?? -1;
    } on PlatformException catch (e) {
      _logError('saveContentFilterRule', e);
      return -1;
    } on MissingPluginException catch (e) {
      _logError('saveContentFilterRule', e);
      return -1;
    }
  }

  /// Update an existing content filter rule.
  static Future<bool> updateContentFilterRule({
    required int id,
    required String phrase,
    String matchMode = 'CONTAINS',
    bool enabled = true,
  }) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'updateContentFilterRule',
        {
          'id': id,
          'phrase': phrase,
          'matchMode': matchMode,
          'enabled': enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('updateContentFilterRule', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('updateContentFilterRule', e);
      return false;
    }
  }

  /// Delete a content filter rule by ID.
  static Future<bool> deleteContentFilterRule(int id) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'deleteContentFilterRule',
        {'id': id},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('deleteContentFilterRule', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('deleteContentFilterRule', e);
      return false;
    }
  }

  /// Enable or disable a content filter rule by ID.
  static Future<bool> setContentFilterRuleEnabled(
    int id,
    bool enabled,
  ) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'setContentFilterRuleEnabled',
        {
          'id': id,
          'enabled': enabled,
        },
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('setContentFilterRuleEnabled', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('setContentFilterRuleEnabled', e);
      return false;
    }
  }

  /// Set the master content filter switch.
  static Future<bool> setMasterContentFilterEnabled(bool enabled) async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>(
        'setMasterContentFilterEnabled',
        {'enabled': enabled},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      _logError('setMasterContentFilterEnabled', e);
      return false;
    } on MissingPluginException catch (e) {
      _logError('setMasterContentFilterEnabled', e);
      return false;
    }
  }

  /// Check if the master content filter switch is enabled.
  static Future<bool> isMasterContentFilterEnabled() async {
    try {
      final result = await PlatformChannels.methodChannel
          .invokeMapMethod<String, dynamic>('isMasterContentFilterEnabled');
      return result?['enabled'] == true;
    } on PlatformException catch (e) {
      _logError('isMasterContentFilterEnabled', e);
      return true;
    } on MissingPluginException catch (e) {
      _logError('isMasterContentFilterEnabled', e);
      return true;
    }
  }

}
