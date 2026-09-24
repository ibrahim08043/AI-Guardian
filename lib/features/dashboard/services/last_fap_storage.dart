import 'package:shared_preferences/shared_preferences.dart';

/// Persistent storage for the "last fap" timestamp.
///
/// Uses SharedPreferences to persist the timestamp across app restarts.
/// On first launch (no saved value), falls back to the development seed:
///   2026-09-21 16:00:00 (local time)
///
/// The timestamp is stored as epoch milliseconds for simplicity and
/// timezone safety.
class LastFapStorage {
  LastFapStorage._();

  static const String _key = 'last_fap_timestamp';

  /// Development seed timestamp: September 21, 2026 4:00 PM local time.
  static final DateTime seedTimestamp = DateTime(2026, 9, 21, 16, 0, 0);

  /// In-memory cache of the last fap timestamp.
  static DateTime? _cachedTimestamp;

  /// Whether the storage has been loaded.
  static bool _isLoaded = false;

  /// Loads the persisted timestamp from SharedPreferences.
  /// If no value exists, uses the seed timestamp.
  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final millis = prefs.getInt(_key);
      if (millis != null) {
        _cachedTimestamp = DateTime.fromMillisecondsSinceEpoch(millis);
      } else {
        _cachedTimestamp = seedTimestamp;
      }
      _isLoaded = true;
    } catch (e) {
      // On error, fall back to seed timestamp.
      _cachedTimestamp = seedTimestamp;
      _isLoaded = true;
    }
  }

  /// Returns the last fap timestamp.
  /// Falls back to seed if not loaded yet (fail-open for UX — not security).
  static DateTime get lastFapTimestamp {
    return _cachedTimestamp ?? seedTimestamp;
  }

  /// Whether the storage has been loaded.
  static bool get isLoaded => _isLoaded;

  /// Saves a new last fap timestamp to SharedPreferences and cache.
  static Future<void> save(DateTime timestamp) async {
    _cachedTimestamp = timestamp;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt(_key, timestamp.millisecondsSinceEpoch);
    } catch (e) {
      // Save to cache even if disk fails — in-memory state is still correct.
    }
  }

  /// Resets internal state for test isolation.
  /// Only used in unit tests.
  static void resetForTesting() {
    _cachedTimestamp = null;
    _isLoaded = false;
  }
}
