import 'package:flutter/material.dart';

import 'app/router.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/password_service.dart';

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
