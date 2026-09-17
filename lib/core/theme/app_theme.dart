import 'package:flutter/material.dart';

/// Material 3 theme configuration for AI Guardian.
/// Uses a calming, wellness-focused color palette appropriate
/// for a digital health and protection application.
class AppTheme {
  AppTheme._();

  // Color palette - calming blues and greens for wellness
  static const Color primaryColor = Color(0xFF2E7D8C);
  static const Color secondaryColor = Color(0xFF4CAF50);
  static const Color tertiaryColor = Color(0xFF5C6BC0);
  static const Color surfaceColor = Color(0xFFF8F9FA);
  static const Color errorColor = Color(0xFFD32F2F);
  static const Color warningColor = Color(0xFFFF9800);

  // Dashboard card colors
  static const Color protectionActiveColor = Color(0xFF4CAF50);
  static const Color protectionInactiveColor = Color(0xFFE57373);
  static const Color restrictedAppsColor = Color(0xFF2196F3);
  static const Color interventionsColor = Color(0xFFFF9800);
  static const Color coachStatusColor = Color(0xFF9C27B0);

  static ThemeData get lightTheme {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primaryColor,
      brightness: Brightness.light,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: surfaceColor,
      appBarTheme: AppBarTheme(
        centerTitle: true,
        elevation: 0,
        backgroundColor: colorScheme.surface,
        foregroundColor: colorScheme.onSurface,
      ),
      cardTheme: CardThemeData(
        elevation: 2,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        indicatorColor: primaryColor.withValues(alpha: 0.2),
      ),
    );
  }
}
