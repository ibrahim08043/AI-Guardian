import 'package:flutter/material.dart';

/// Analytics screen - view usage statistics and trends.
/// This screen will display:
/// - Daily/weekly/monthly usage charts
/// - App-specific usage breakdown
/// - Focus session history
/// - Intervention statistics
class AnalyticsScreen extends StatelessWidget {
  const AnalyticsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Analytics'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (value) {
              // TODO: Change time period
            },
            itemBuilder: (context) => [
              const PopupMenuItem(value: 'day', child: Text('Today')),
              const PopupMenuItem(value: 'week', child: Text('This Week')),
              const PopupMenuItem(value: 'month', child: Text('This Month')),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Summary cards
              const Row(
                children: [
                  Expanded(
                    child: _SummaryCard(
                      title: 'Total Screen Time',
                      value: '4h 32m',
                      icon: Icons.timer,
                      color: Colors.blue,
                    ),
                  ),
                  SizedBox(width: 12),
                  Expanded(
                    child: _SummaryCard(
                      title: 'Focus Time',
                      value: '2h 15m',
                      icon: Icons.center_focus_strong,
                      color: Colors.green,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              const Row(
                children: [
                  Expanded(
                    child: _SummaryCard(
                      title: 'Blocks',
                      value: '8',
                      icon: Icons.block,
                      color: Colors.orange,
                    ),
                  ),
                  SizedBox(width: 12),
                  Expanded(
                    child: _SummaryCard(
                      title: 'Streak',
                      value: '5 days',
                      icon: Icons.local_fire_department,
                      color: Colors.purple,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 24),

              // Weekly chart placeholder
              Text(
                'Weekly Overview',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      const SizedBox(
                        height: 150,
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            _BarChartBar(label: 'Mon', height: 80),
                            _BarChartBar(label: 'Tue', height: 120),
                            _BarChartBar(label: 'Wed', height: 60),
                            _BarChartBar(label: 'Thu', height: 90),
                            _BarChartBar(label: 'Fri', height: 70),
                            _BarChartBar(label: 'Sat', height: 110),
                            _BarChartBar(label: 'Sun', height: 50),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Screen time (hours)',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),

              // App usage breakdown
              Text(
                'App Usage',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      _AppUsageRow(
                        appName: 'Instagram',
                        usage: '45m',
                        percentage: 0.3,
                        color: Colors.pink,
                      ),
                      SizedBox(height: 12),
                      _AppUsageRow(
                        appName: 'Twitter',
                        usage: '32m',
                        percentage: 0.22,
                        color: Colors.lightBlue,
                      ),
                      SizedBox(height: 12),
                      _AppUsageRow(
                        appName: 'YouTube',
                        usage: '1h 15m',
                        percentage: 0.45,
                        color: Colors.red,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final String title;
  final String value;
  final IconData icon;
  final Color color;

  const _SummaryCard({
    required this.title,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color, size: 24),
            const SizedBox(height: 8),
            Text(
              title,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              value,
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BarChartBar extends StatelessWidget {
  final String label;
  final double height;

  const _BarChartBar({required this.label, required this.height});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Container(
          width: 24,
          height: height,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primary,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
        const SizedBox(height: 4),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}

class _AppUsageRow extends StatelessWidget {
  final String appName;
  final String usage;
  final double percentage;
  final Color color;

  const _AppUsageRow({
    required this.appName,
    required this.usage,
    required this.percentage,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(
            color: color,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(appName, style: Theme.of(context).textTheme.bodyMedium),
        ),
        Text(
          usage,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }
}
