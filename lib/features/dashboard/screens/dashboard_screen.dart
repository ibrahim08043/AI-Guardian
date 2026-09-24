import 'dart:async';

import 'package:flutter/material.dart';

import '../services/last_fap_storage.dart';
import '../utils/dashboard_utils.dart';

/// Dashboard screen — the main view after app launch.
///
/// Displays:
/// - Dynamic time-of-day greeting
/// - Live current time (updates every second)
/// - Current date and day of week
/// - Elapsed time since last fap event
/// - "FAPPED AGAIN" button to reset the timer
///
/// All values are computed from [DateTime.now()] and the persisted
/// last fap timestamp — nothing is hardcoded.
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with WidgetsBindingObserver {
  Timer? _timer;
  late DateTime _now;
  late DateTime _lastFap;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _now = DateTime.now();
    _lastFap = LastFapStorage.lastFapTimestamp;
    _startTimer();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Refresh immediately when app returns from background.
      setState(() {
        _now = DateTime.now();
        _lastFap = LastFapStorage.lastFapTimestamp;
      });
    }
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _now = DateTime.now();
      });
    });
  }

  void _onFappedAgain() async {
    final now = DateTime.now();
    await LastFapStorage.save(now);
    setState(() {
      _now = now;
      _lastFap = now;
    });
  }

  @override
  Widget build(BuildContext context) {
    final elapsed = _now.difference(_lastFap);

    return Scaffold(
      appBar: AppBar(
        title: const Text('AI Guardian'),
        actions: [
          IconButton(
            icon: const Icon(Icons.notifications_outlined),
            onPressed: () {
              // TODO: Show notifications
            },
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Dynamic greeting
              Text(
                getGreeting(_now),
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
              ),
              const SizedBox(height: 24),

              // Live clock
              Text(
                formatTime(_now),
                style: Theme.of(context).textTheme.displaySmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Theme.of(context).colorScheme.primary,
                    ),
              ),
              const SizedBox(height: 4),

              // Live date
              Text(
                formatDate(_now),
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 32),

              // Elapsed timer section
              Text(
                'Time since last reset',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
              ),
              const SizedBox(height: 12),
              Text(
                formatElapsed(elapsed),
                style: Theme.of(context).textTheme.displaySmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      fontFamily: 'monospace',
                    ),
              ),
              const SizedBox(height: 24),

              // FAPPED AGAIN button
              SizedBox(
                width: double.infinity,
                height: 56,
                child: FilledButton(
                  onPressed: _onFappedAgain,
                  style: FilledButton.styleFrom(
                    backgroundColor:
                        Theme.of(context).colorScheme.errorContainer,
                    foregroundColor:
                        Theme.of(context).colorScheme.onErrorContainer,
                  ),
                  child: const Text(
                    'FAPPED AGAIN',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 16,
                    ),
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
