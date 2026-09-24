import 'package:flutter_test/flutter_test.dart';
import 'package:ai_guardian/features/dashboard/utils/dashboard_utils.dart';

void main() {
  group('getGreeting', () {
    test('morning (5:00 AM)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 5, 0)), 'Good Morning');
    });

    test('morning (11:59 AM)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 11, 59)), 'Good Morning');
    });

    test('afternoon (12:00 PM)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 12, 0)), 'Good Afternoon');
    });

    test('afternoon (16:59)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 16, 59)), 'Good Afternoon');
    });

    test('evening (17:00)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 17, 0)), 'Good Evening');
    });

    test('evening (20:59)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 20, 59)), 'Good Evening');
    });

    test('night (21:00)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 21, 0)), 'Good Night');
    });

    test('night (4:59 AM)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 4, 59)), 'Good Night');
    });

    test('night (0:00 midnight)', () {
      expect(getGreeting(DateTime(2026, 9, 24, 0, 0)), 'Good Night');
    });
  });

  group('formatTime', () {
    test('formats 12-hour time with seconds', () {
      expect(formatTime(DateTime(2026, 9, 24, 21, 21, 37)), '9:21:37 PM');
    });

    test('formats morning time', () {
      expect(formatTime(DateTime(2026, 9, 24, 9, 5, 3)), '9:05:03 AM');
    });

    test('formats midnight as 12 AM', () {
      expect(formatTime(DateTime(2026, 9, 24, 0, 0, 0)), '12:00:00 AM');
    });

    test('formats noon as 12 PM', () {
      expect(formatTime(DateTime(2026, 9, 24, 12, 0, 0)), '12:00:00 PM');
    });
  });

  group('formatDate', () {
    test('formats full date string', () {
      expect(
        formatDate(DateTime(2026, 9, 24)),
        'Thursday, September 24, 2026',
      );
    });

    test('formats January 1', () {
      expect(formatDate(DateTime(2026, 1, 1)), 'Thursday, January 1, 2026');
    });

    test('formats December 31', () {
      expect(
        formatDate(DateTime(2026, 12, 31)),
        'Thursday, December 31, 2026',
      );
    });
  });

  group('formatElapsed', () {
    test('formats known duration', () {
      final d = Duration(days: 3, hours: 5, minutes: 21, seconds: 37);
      expect(formatElapsed(d), '3d 5h 21m 37s');
    });

    test('formats zero duration', () {
      expect(formatElapsed(Duration.zero), '0d 0h 0m 0s');
    });

    test('formats seconds only', () {
      expect(formatElapsed(const Duration(seconds: 45)), '0d 0h 0m 45s');
    });

    test('formats minutes and seconds', () {
      expect(
        formatElapsed(const Duration(minutes: 5, seconds: 30)),
        '0d 0h 5m 30s',
      );
    });

    test('formats days only', () {
      expect(formatElapsed(const Duration(days: 7)), '7d 0h 0m 0s');
    });

    test('negative duration returns zero', () {
      expect(formatElapsed(const Duration(seconds: -5)), '0d 0h 0m 0s');
    });

    test('exactly one second progression', () {
      final d1 = const Duration(seconds: 10);
      final d2 = d1 + const Duration(seconds: 1);
      expect(formatElapsed(d1), '0d 0h 0m 10s');
      expect(formatElapsed(d2), '0d 0h 0m 11s');
    });
  });

  group('Elapsed calculation from seed', () {
    test('correct elapsed from seed to known time', () {
      // Seed: 2026-09-21 16:00:00
      // At:   2026-09-24 21:21:37
      // Elapsed: 3 days, 5 hours, 21 minutes, 37 seconds
      final seed = DateTime(2026, 9, 21, 16, 0, 0);
      final now = DateTime(2026, 9, 24, 21, 21, 37);
      final elapsed = now.difference(seed);
      expect(elapsed.inDays, 3);
      expect(elapsed.inHours % 24, 5);
      expect(elapsed.inMinutes % 60, 21);
      expect(elapsed.inSeconds % 60, 37);
      expect(formatElapsed(elapsed), '3d 5h 21m 37s');
    });

    test('future timestamp returns zero', () {
      final seed = DateTime(2026, 9, 21, 16, 0, 0);
      final now = DateTime(2026, 9, 20, 10, 0, 0); // before seed
      final elapsed = now.difference(seed);
      expect(formatElapsed(elapsed), '0d 0h 0m 0s');
    });
  });
}
