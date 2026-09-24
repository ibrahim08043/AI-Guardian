import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ai_guardian/features/dashboard/services/last_fap_storage.dart';
import 'package:ai_guardian/features/dashboard/utils/dashboard_utils.dart';

void main() {
  setUp(() {
    // Clear SharedPreferences before each test.
    SharedPreferences.setMockInitialValues({});
    // Reset the storage state.
    LastFapStorage.resetForTesting();
  });

  group('LastFapStorage', () {
    test('no saved timestamp returns seed value', () async {
      await LastFapStorage.load();
      expect(
        LastFapStorage.lastFapTimestamp,
        DateTime(2026, 9, 21, 16, 0, 0),
      );
    });

    test('save and load returns same value', () async {
      await LastFapStorage.load();
      final savedTime = DateTime(2026, 9, 24, 21, 25, 42);
      await LastFapStorage.save(savedTime);
      expect(LastFapStorage.lastFapTimestamp, savedTime);
    });

    test('save overwrites previous value', () async {
      await LastFapStorage.load();
      final first = DateTime(2026, 9, 24, 21, 0, 0);
      final second = DateTime(2026, 9, 24, 22, 0, 0);
      await LastFapStorage.save(first);
      expect(LastFapStorage.lastFapTimestamp, first);
      await LastFapStorage.save(second);
      expect(LastFapStorage.lastFapTimestamp, second);
    });

    test('persists across separate load calls', () async {
      await LastFapStorage.load();
      final savedTime = DateTime(2026, 9, 24, 21, 25, 42);
      await LastFapStorage.save(savedTime);

      // Simulate app restart by loading again.
      await LastFapStorage.load();
      expect(LastFapStorage.lastFapTimestamp, savedTime);
    });

    test('isLoaded reflects load state', () async {
      expect(LastFapStorage.isLoaded, false);
      await LastFapStorage.load();
      expect(LastFapStorage.isLoaded, true);
    });

    test('fapped again resets timestamp', () async {
      await LastFapStorage.load();
      // Initially at seed.
      expect(
        LastFapStorage.lastFapTimestamp,
        DateTime(2026, 9, 21, 16, 0, 0),
      );

      // Simulate "fapped again" with a new timestamp.
      final newTime = DateTime(2026, 9, 24, 21, 30, 0);
      await LastFapStorage.save(newTime);
      expect(LastFapStorage.lastFapTimestamp, newTime);

      // Verify elapsed from new timestamp is correct.
      final elapsed = DateTime.now().difference(newTime);
      expect(elapsed.isNegative, isFalse);
      expect(formatElapsed(elapsed), isNotEmpty);
    });
  });
}
