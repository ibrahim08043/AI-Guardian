/// Pure helper functions for the Dashboard.
/// These are stateless, easily testable, and have no Flutter dependencies.

/// Returns a time-of-day greeting based on the hour.
///
/// Thresholds:
///   05:00–11:59 → Good Morning
///   12:00–16:59 → Good Afternoon
///   17:00–20:59 → Good Evening
///   21:00–04:59 → Good Night
String getGreeting(DateTime now) {
  final hour = now.hour;
  if (hour >= 5 && hour < 12) return 'Good Morning';
  if (hour >= 12 && hour < 17) return 'Good Afternoon';
  if (hour >= 17 && hour < 21) return 'Good Evening';
  return 'Good Night';
}

/// Formats a DateTime as a 12-hour clock with seconds.
/// Example: "09:21:37 PM"
String formatTime(DateTime now) {
  final h12 = now.hour == 0 ? 12 : (now.hour > 12 ? now.hour - 12 : now.hour);
  final m = now.minute.toString().padLeft(2, '0');
  final s = now.second.toString().padLeft(2, '0');
  final period = now.hour < 12 ? 'AM' : 'PM';
  return '$h12:$m:$s $period';
}

/// Formats a DateTime as a full date string.
/// Example: "Thursday, September 24, 2026"
String formatDate(DateTime now) {
  const days = [
    'Monday',
    'Tuesday',
    'Wednesday',
    'Thursday',
    'Friday',
    'Saturday',
    'Sunday',
  ];
  const months = [
    'January',
    'February',
    'March',
    'April',
    'May',
    'June',
    'July',
    'August',
    'September',
    'October',
    'November',
    'December',
  ];
  return '${days[now.weekday - 1]}, ${months[now.month - 1]} ${now.day}, ${now.year}';
}

/// Formats a Duration as a compact elapsed string.
/// Example: "3d 5h 21m 37s"
///
/// Never returns negative values — if the duration is negative (future
/// timestamp), returns "0d 0h 0m 0s".
String formatElapsed(Duration d) {
  if (d.isNegative) return '0d 0h 0m 0s';
  final days = d.inDays;
  final hours = d.inHours % 24;
  final minutes = d.inMinutes % 60;
  final seconds = d.inSeconds % 60;
  return '${days}d ${hours}h ${minutes}m ${seconds}s';
}
