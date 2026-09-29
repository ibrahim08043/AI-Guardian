import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ai_guardian/features/dashboard/screens/dashboard_screen.dart';
import 'package:ai_guardian/features/dashboard/services/last_fap_storage.dart';

/// Helper to wrap widget in MaterialApp for testing.
Widget buildTestApp() {
  return MaterialApp(
    home: const DashboardScreen(),
  );
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    LastFapStorage.resetForTesting();
  });

  group('DashboardScreen', () {
    testWidgets('renders Start Timer From button', (tester) async {
      await LastFapStorage.load();
      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      expect(find.text('Start Timer From'), findsOneWidget);
      expect(find.byIcon(Icons.calendar_today), findsOneWidget);
    });

    testWidgets('renders FAPPED AGAIN button', (tester) async {
      await LastFapStorage.load();
      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      expect(find.text('FAPPED AGAIN'), findsOneWidget);
    });

    testWidgets('tapping FAPPED AGAIN saves current time', (tester) async {
      await LastFapStorage.load();
      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      final before = DateTime.now();
      await tester.tap(find.text('FAPPED AGAIN'));
      await tester.pumpAndSettle();
      final after = DateTime.now();

      final saved = LastFapStorage.lastFapTimestamp;
      expect(
        saved.isAfter(before.subtract(const Duration(seconds: 1))) &&
            saved.isBefore(after.add(const Duration(seconds: 1))),
        isTrue,
      );
    });

    testWidgets('tapping Start Timer From opens date picker',
        (tester) async {
      await LastFapStorage.load();
      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Start Timer From'));
      await tester.pumpAndSettle();

      // Date picker should show an "OK" confirm button
      expect(find.text('OK'), findsOneWidget);
    });

    testWidgets('selecting past date and time saves to storage',
        (tester) async {
      await LastFapStorage.load();
      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      // Tap "Start Timer From"
      await tester.tap(find.text('Start Timer From'));
      await tester.pumpAndSettle();

      // In date picker, select day 15 (guaranteed to be in the past)
      await tester.tap(find.text('15'));
      await tester.pumpAndSettle();

      // Confirm date
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      // Time picker should now be visible — confirm with "OK"
      expect(find.text('OK'), findsOneWidget);
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      // The saved timestamp should now be the custom date/time
      final saved = LastFapStorage.lastFapTimestamp;
      expect(saved.day, 15);
    });
  });
}
