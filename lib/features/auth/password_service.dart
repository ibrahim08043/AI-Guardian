import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Service for reading and verifying the app password.
///
/// The password is read from Flutter assets (app_password.txt) at startup
/// using rootBundle. The password content is never stored in SharedPreferences,
/// SQLite, logs, or any persistent location beyond the in-memory cache.
class PasswordService {
  PasswordService._();

  /// In-memory cache of the stored password.
  /// Loaded once from assets, never persisted to disk.
  static String? _storedPassword;

  /// Whether the password has been loaded from assets.
  static bool _isLoaded = false;

  /// Loads the password from Flutter assets into memory.
  /// Must be called once at app startup before any auth checks.
  static Future<void> loadPassword() async {
    try {
      _storedPassword = await rootBundle.loadString('assets/app_password.txt');
      // Trim trailing newlines/line-endings only.
      _storedPassword = _storedPassword!.trimRight();
      _isLoaded = true;
      debugPrint('[PasswordService] Password loaded from assets');
    } catch (e) {
      debugPrint('[PasswordService] Failed to load password: $e');
      // FAIL-CLOSED: If we can't load the password, treat it as "password
      // exists but we can't read it". This prevents unauthorized access
      // when the asset file is missing or corrupted.
      _storedPassword = null;
      _isLoaded = true;
    }
  }

  /// Checks whether a non-empty password is configured.
  /// Returns true if a password exists (or if loading failed — fail-closed).
  /// Returns false ONLY when we successfully loaded and confirmed no password
  /// file exists (empty/null content after successful read).
  static bool hasPassword() {
    if (!_isLoaded) return true; // Fail-closed: assume password exists.
    // If loading failed (_storedPassword is null), treat as password exists.
    if (_storedPassword == null) return true;
    return _storedPassword!.isNotEmpty;
  }

  /// Verifies the given [password] against the stored password.
  /// Returns true if the password matches, false otherwise.
  static bool verify(String password) {
    if (!_isLoaded) return false; // Fail-closed: deny on error.
    if (_storedPassword == null || _storedPassword!.isEmpty) return false;
    return password == _storedPassword;
  }
}
