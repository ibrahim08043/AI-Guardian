
/// Utility functions for the AI Guardian application.
class AppUtils {
  AppUtils._();

  /// Get a greeting based on the current time of day.
  static String getGreeting() {
    final hour = DateTime.now().hour;
    if (hour < 12) {
      return 'Good morning!';
    } else if (hour < 17) {
      return 'Good afternoon!';
    } else {
      return 'Good evening!';
    }
  }

  /// Format duration in a human-readable format.
  static String formatDuration(Duration duration) {
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);

    if (hours > 0) {
      return '${hours}h ${minutes}m';
    }
    return '${minutes}m';
  }

  /// Format a percentage as a string.
  static String formatPercentage(double value) {
    return '${(value * 100).toStringAsFixed(0)}%';
  }
}
