import 'package:go_router/go_router.dart';

import '../core/constants/app_constants.dart';
import '../features/onboarding/screens/onboarding_screen.dart';
import '../features/dashboard/screens/dashboard_screen.dart';
import '../features/restrictions/screens/restrictions_screen.dart';
import '../features/coach/screens/coach_screen.dart';
import '../features/analytics/screens/analytics_screen.dart';
import '../features/settings/screens/settings_screen.dart';
import '../core/widgets/main_scaffold.dart';

/// Application router configuration.
/// Uses GoRouter for declarative, URL-based routing.
/// This approach is chosen over named routes for:
/// - Better support for nested navigation
/// - Type-safe route parameters
/// - Easy integration with bottom navigation
/// - Simpler deep linking if needed in future
final GoRouter appRouter = GoRouter(
  initialLocation: AppConstants.onboardingRoute,
  routes: [
    GoRoute(
      path: AppConstants.onboardingRoute,
      builder: (context, state) => const OnboardingScreen(),
    ),
    ShellRoute(
      builder: (context, state, child) => MainScaffold(child: child),
      routes: [
        GoRoute(
          path: AppConstants.dashboardRoute,
          builder: (context, state) => const DashboardScreen(),
        ),
        GoRoute(
          path: AppConstants.restrictionsRoute,
          builder: (context, state) => const RestrictionsScreen(),
        ),
        GoRoute(
          path: AppConstants.coachRoute,
          builder: (context, state) => const CoachScreen(),
        ),
        GoRoute(
          path: AppConstants.analyticsRoute,
          builder: (context, state) => const AnalyticsScreen(),
        ),
        GoRoute(
          path: AppConstants.settingsRoute,
          builder: (context, state) => const SettingsScreen(),
        ),
      ],
    ),
  ],
);
