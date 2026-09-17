import 'package:flutter/services.dart';

/// Centralized platform channel definitions for Flutter <-> Kotlin communication.
///
/// Channel naming follows the project namespace: com.aiguardian.ai_guardian
///
/// IMPORTANT: Flutter is NOT on the critical restriction enforcement path.
/// The native Kotlin layer handles blocking directly without waiting for Flutter.
/// These channels exist for UI feedback and configuration only.
class PlatformChannels {
  PlatformChannels._();

  // ---------------------------------------------------------------------------
  // Channel names — single source of truth for both Flutter and Kotlin sides.
  // ---------------------------------------------------------------------------

  static const String _methodChannelName =
      'com.aiguardian.ai_guardian/method';
  static const String _eventChannelName =
      'com.aiguardian.ai_guardian/event';

  // ---------------------------------------------------------------------------
  // Channel instances
  // ---------------------------------------------------------------------------

  /// Method channel for request/response communication with native layer.
  ///
  /// Current methods:
  /// - getPlatformInfo
  /// - checkAccessibilityStatus
  /// - openAccessibilitySettings
  /// - getAppVersion
  static const MethodChannel methodChannel =
      MethodChannel(_methodChannelName);

  /// Event channel for streaming events from native layer to Flutter.
  ///
  /// Future events (NOT implemented yet):
  /// - foreground_app_changed
  /// - restriction_triggered
  /// - intervention_completed
  static const EventChannel eventChannel =
      EventChannel(_eventChannelName);
}
