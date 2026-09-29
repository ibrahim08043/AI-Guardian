import 'package:flutter/material.dart';

import 'app/router.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/password_service.dart';
import 'features/auth/accessibility_password_service.dart';
import 'features/dashboard/services/last_fap_storage.dart';
import 'platform/android_platform_service.dart';

/// Entry point for AI Guardian application.
/// This app uses a clean feature-first architecture with:
/// - Flutter for UI layer
/// - Kotlin native layer for critical operations (AccessibilityService, Policy Engine)
/// - GoRouter for navigation
/// - Material 3 design system
void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Load the app password from assets before rendering any UI.
  // This ensures the auth gate can check synchronously.
  await PasswordService.loadPassword();

  // Load the accessibility security password independently.
  // This is a completely separate credential from the app password.
  await AccessibilityPasswordService.loadPassword();

  // Load the persisted last fap timestamp for the Dashboard timer.
  await LastFapStorage.load();

  // Import the default block list (websites + keywords) from assets/list.txt.
  // This is idempotent: only inserts rules that don't already exist.
  // Runs asynchronously — does not block app startup.
  AndroidPlatformService.importDefaultBlockList().then((result) {
    if (result != null) {
      debugPrint('[main] Default block list imported: $result');
    }
  }).catchError((e) {
    debugPrint('[main] Default block list import failed (non-fatal): $e');
  });

  runApp(const AiGuardianApp());
}

class AiGuardianApp extends StatelessWidget {
  const AiGuardianApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'AI Guardian',
      theme: AppTheme.lightTheme,
      routerConfig: appRouter,
      debugShowCheckedModeBanner: false,
    );
  }
}
