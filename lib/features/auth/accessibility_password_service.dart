import 'package:flutter/foundation.dart';

import 'package:flutter/services.dart';

/// Service for reading and verifying the Accessibility Security password.
///
/// This is a completely separate credential from the app password managed
/// by [PasswordService]. The two passwords are stored in different asset
/// files and are never cross-referenced.
///
/// - App password: assets/app_password.txt (managed by PasswordService)
/// - Accessibility password: assets/accessibility_password.txt (managed here)
///
/// The password is read from Flutter assets at startup using rootBundle.
/// It is never stored in SharedPreferences, SQLite, logs, or any persistent
/// location beyond the in-memory cache.
class AccessibilityPasswordService {
  AccessibilityPasswordService._();

  /// In-memory cache of the stored accessibility password.
  /// Loaded once from assets, never persisted to disk.
  static String? _storedPassword;

  /// Whether the password has been loaded from assets.
  static bool _isLoaded = false;

  /// Loads the accessibility password from Flutter assets into memory.
  /// Must be called once at app startup before any auth checks.
  static Future<void> loadPassword() async {
    try {
      _storedPassword =
          await rootBundle.loadString('assets/accessibility_password.txt');
      // Trim trailing newlines/line-endings only.
      _storedPassword = _storedPassword!.trimRight();
      _isLoaded = true;
      debugPrint(
          '[AccessibilityPasswordService] Password loaded from assets');
    } catch (e) {
      debugPrint(
          '[AccessibilityPasswordService] Failed to load password: $e');
      // FAIL-CLOSED: If we can't load the password, treat it as "password
      // exists but we can't read it". This prevents unauthorized access
      // when the asset file is missing or corrupted.
      _storedPassword = null;
      _isLoaded = true;
    }
  }

  /// Checks whether a non-empty accessibility password is configured.
  /// Returns true if a password exists (or if loading failed — fail-closed).
  /// Returns false ONLY when we successfully loaded and confirmed no password
  /// file exists (empty/null content after successful read).
  static bool hasPassword() {
    if (!_isLoaded) return true; // Fail-closed: assume password exists.
    // If loading failed (_storedPassword is null), treat as password exists.
    if (_storedPassword == null) return true;
    return _storedPassword!.isNotEmpty;
  }

  /// Verifies the given [password] against the stored accessibility password.
  /// Returns true if the password matches, false otherwise.
  static bool verify(String password) {
    if (!_isLoaded) return false; // Fail-closed: deny on error.
    if (_storedPassword == null || _storedPassword!.isEmpty) return false;
    return password == _storedPassword;
  }
}
