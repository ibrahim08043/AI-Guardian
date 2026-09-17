import 'package:flutter/material.dart';

import '../../../platform/android_platform_service.dart';
import '../../../platform/models/policy_model.dart';

/// Restriction settings screen for a single app.
///
/// Allows users to configure:
/// - Block/Allow toggle
/// - Schedule (time-based blocking)
/// - Daily usage limit
class RestrictionSettingsScreen extends StatefulWidget {
  final String packageName;
  final String appName;
  final String? iconPath;

  const RestrictionSettingsScreen({
    super.key,
    required this.packageName,
    required this.appName,
    this.iconPath,
  });

  @override
  State<RestrictionSettingsScreen> createState() =>
      _RestrictionSettingsScreenState();
}

class _RestrictionSettingsScreenState extends State<RestrictionSettingsScreen> {
  bool _isLoading = true;
  bool _isSaving = false;
  String? _errorMessage;

  // Current policy state
  bool _isRestricted = false;
  ScheduleModel _schedule = const ScheduleModel();
  DailyLimitModel _dailyLimit = const DailyLimitModel();

  // Usage tracking
  int _todayUsageMinutes = 0;

  @override
  void initState() {
    super.initState();
    _loadPolicy();
  }

  Future<void> _loadPolicy() async {
    try {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });

      // Load full policy with Phase C fields
      final policy = await AndroidPlatformService.getFullPolicy(widget.packageName);

      // Load today's usage
      final usage = await AndroidPlatformService.getUsageToday(widget.packageName);

      if (!mounted) return;

      setState(() {
        _isLoading = false;
        if (policy != null) {
          _isRestricted = policy.enabled && policy.action == PolicyActionModel.block;
          _schedule = policy.schedule;
          _dailyLimit = policy.dailyLimit;
        }
        _todayUsageMinutes = usage?['usageMinutes'] as int? ?? 0;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = 'Failed to load settings: $e';
      });
    }
  }

  Future<void> _toggleRestriction(bool value) async {
    try {
      setState(() => _isSaving = true);

      if (value) {
        // Enable restriction: save a BLOCK policy
        final policy = PolicyModel(
          packageName: widget.packageName,
          action: PolicyActionModel.block,
          enabled: true,
        );
        await AndroidPlatformService.savePolicy(policy);
      } else {
        // Disable restriction.
        // If schedule or daily limit is configured, keep the policy row alive
        // with action=ALLOW — the schedule/limit remain the sole restriction.
        // Only delete the row if there are no active restrictions at all.
        final hasSchedule = _schedule.enabled && _schedule.startMinutes != null;
        final hasLimit = _dailyLimit.enabled && _dailyLimit.limitMinutes != null;

        if (hasSchedule || hasLimit) {
          // Update policy to ALLOW so schedule/limit stay in effect
          final policy = PolicyModel(
            packageName: widget.packageName,
            action: PolicyActionModel.allow,
            enabled: true,
          );
          await AndroidPlatformService.savePolicy(policy);
        } else {
          // No schedule or limit — safe to delete the policy row entirely
          await AndroidPlatformService.deletePolicy(widget.packageName);
        }
      }

      if (!mounted) return;

      setState(() {
        _isRestricted = value;
        _isSaving = false;
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(value ? '${widget.appName} restricted' : '${widget.appName} unrestricted'),
          duration: const Duration(seconds: 2),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isSaving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: $e')),
      );
    }
  }

  Future<void> _configureSchedule() async {
    bool scheduleEnabled = _schedule.enabled;
    int startHour = _schedule.startMinutes != null ? _schedule.startMinutes! ~/ 60 : 18;
    int startMin = _schedule.startMinutes != null ? _schedule.startMinutes! % 60 : 0;
    int endHour = _schedule.endMinutes != null ? _schedule.endMinutes! ~/ 60 : 21;
    int endMin = _schedule.endMinutes != null ? _schedule.endMinutes! % 60 : 0;

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Schedule Blocking'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SwitchListTile(
                  title: const Text('Enable Schedule'),
                  subtitle: const Text('Block app during specific hours'),
                  value: scheduleEnabled,
                  onChanged: (v) => setDialogState(() => scheduleEnabled = v),
                ),
                if (scheduleEnabled) ...[
                  const SizedBox(height: 16),
                  ListTile(
                    title: const Text('Start Time'),
                    subtitle: Text(ScheduleModel.formatTime(startHour * 60 + startMin)),
                    trailing: const Icon(Icons.access_time),
                    onTap: () async {
                      final picked = await showTimePicker(
                        context: context,
                        initialTime: TimeOfDay(hour: startHour, minute: startMin),
                      );
                      if (picked != null) {
                        setDialogState(() {
                          startHour = picked.hour;
                          startMin = picked.minute;
                        });
                      }
                    },
                  ),
                  ListTile(
                    title: const Text('End Time'),
                    subtitle: Text(ScheduleModel.formatTime(endHour * 60 + endMin)),
                    trailing: const Icon(Icons.access_time),
                    onTap: () async {
                      final picked = await showTimePicker(
                        context: context,
                        initialTime: TimeOfDay(hour: endHour, minute: endMin),
                      );
                      if (picked != null) {
                        setDialogState(() {
                          endHour = picked.hour;
                          endMin = picked.minute;
                        });
                      }
                    },
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );

    if (result == true) {
      try {
        setState(() => _isSaving = true);

        final startMinutes = ScheduleModel.toMinutes(startHour, startMin);
        final endMinutes = ScheduleModel.toMinutes(endHour, endMin);

        await AndroidPlatformService.saveSchedule(
          packageName: widget.packageName,
          enabled: scheduleEnabled,
          startMinutes: scheduleEnabled ? startMinutes : null,
          endMinutes: scheduleEnabled ? endMinutes : null,
        );

        if (!mounted) return;

        setState(() {
          _schedule = ScheduleModel(
            enabled: scheduleEnabled,
            startMinutes: scheduleEnabled ? startMinutes : null,
            endMinutes: scheduleEnabled ? endMinutes : null,
          );
          _isSaving = false;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Schedule updated')),
        );
      } catch (e) {
        if (!mounted) return;
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error saving schedule: $e')),
        );
      }
    }
  }

  Future<void> _configureDailyLimit() async {
    bool limitEnabled = _dailyLimit.enabled;
    int limitMinutes = _dailyLimit.limitMinutes ?? 30;

    final controller = TextEditingController(text: limitMinutes.toString());

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Daily Usage Limit'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SwitchListTile(
                  title: const Text('Enable Limit'),
                  subtitle: const Text('Block app after daily usage quota'),
                  value: limitEnabled,
                  onChanged: (v) => setDialogState(() => limitEnabled = v),
                ),
                if (limitEnabled) ...[
                  const SizedBox(height: 16),
                  TextField(
                    controller: controller,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Daily limit (minutes)',
                      hintText: 'e.g., 30',
                      border: OutlineInputBorder(),
                      suffixText: 'minutes',
                    ),
                    onChanged: (v) {
                      final parsed = int.tryParse(v);
                      if (parsed != null) {
                        setDialogState(() => limitMinutes = parsed);
                      }
                    },
                  ),
                  const SizedBox(height: 8),
                  Text(
                    DailyLimitModel(
                      enabled: true,
                      limitMinutes: limitMinutes,
                    ).displayString(),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );

    if (result == true) {
      try {
        setState(() => _isSaving = true);

        await AndroidPlatformService.saveDailyLimit(
          packageName: widget.packageName,
          enabled: limitEnabled,
          limitMinutes: limitEnabled ? limitMinutes : null,
        );

        if (!mounted) return;

        setState(() {
          _dailyLimit = DailyLimitModel(
            enabled: limitEnabled,
            limitMinutes: limitEnabled ? limitMinutes : null,
          );
          _isSaving = false;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Daily limit updated')),
        );
      } catch (e) {
        if (!mounted) return;
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error saving limit: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.appName),
        actions: [
          if (_isSaving)
            const Padding(
              padding: EdgeInsets.all(16),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _errorMessage != null
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(_errorMessage!),
                      const SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: _loadPolicy,
                        child: const Text('Retry'),
                      ),
                    ],
                  ),
                )
              : ListView(
                  children: [
                    // --- Main Toggle ---
                    Card(
                      margin: const EdgeInsets.all(16),
                      child: SwitchListTile(
                        title: const Text('Restrict App'),
                        subtitle: Text(
                          _isRestricted ? 'This app is blocked' : 'This app is allowed',
                        ),
                        secondary: Icon(
                          _isRestricted ? Icons.block : Icons.check_circle,
                          color: _isRestricted ? Colors.red : Colors.green,
                        ),
                        value: _isRestricted,
                        onChanged: _toggleRestriction,
                      ),
                    ),

                    // --- Schedule Section ---
                    Card(
                      margin: const EdgeInsets.symmetric(horizontal: 16),
                      child: ListTile(
                        leading: const Icon(Icons.schedule),
                        title: const Text('Schedule'),
                        subtitle: Text(
                          _schedule.enabled
                              ? _schedule.displayString()
                              : 'Not configured',
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (_schedule.enabled)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 8,
                                  vertical: 2,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.orange.shade100,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: const Text(
                                  'ON',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: Colors.orange,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                            const SizedBox(width: 8),
                            const Icon(Icons.chevron_right),
                          ],
                        ),
                        onTap: _configureSchedule,
                      ),
                    ),

                    const SizedBox(height: 8),

                    // --- Daily Limit Section ---
                    Card(
                      margin: const EdgeInsets.symmetric(horizontal: 16),
                      child: ListTile(
                        leading: const Icon(Icons.timer),
                        title: const Text('Daily Limit'),
                        subtitle: Text(
                          _dailyLimit.enabled
                              ? '${_dailyLimit.displayString()} (used: ${_todayUsageMinutes}min)'
                              : 'Not configured',
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (_dailyLimit.enabled)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 8,
                                  vertical: 2,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.blue.shade100,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  '${_todayUsageMinutes}/${_dailyLimit.limitMinutes}m',
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: Colors.blue,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                            const SizedBox(width: 8),
                            const Icon(Icons.chevron_right),
                          ],
                        ),
                        onTap: _configureDailyLimit,
                      ),
                    ),

                    const SizedBox(height: 16),

                    // --- Usage Info ---
                    if (_dailyLimit.enabled)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text(
                                  'Today\'s Usage',
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                const SizedBox(height: 8),
                                LinearProgressIndicator(
                                  value: _dailyLimit.limitMinutes != null &&
                                          _dailyLimit.limitMinutes! > 0
                                      ? (_todayUsageMinutes / _dailyLimit.limitMinutes!)
                                          .clamp(0.0, 1.0)
                                      : 0,
                                  backgroundColor: Colors.grey.shade200,
                                  color: _todayUsageMinutes >= (_dailyLimit.limitMinutes ?? 0)
                                      ? Colors.red
                                      : Colors.blue,
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  '$_todayUsageMinutes of ${_dailyLimit.limitMinutes} minutes used',
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
    );
  }
}
