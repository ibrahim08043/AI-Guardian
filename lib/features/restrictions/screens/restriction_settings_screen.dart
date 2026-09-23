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
  List<AppScheduleModel> _schedules = [];
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
          _schedules = policy.schedules;
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
        // If schedules or daily limit are configured, keep the policy row alive
        // with action=ALLOW — the schedules/limit remain the sole restriction.
        // Only delete the row if there are no active restrictions at all.
        final hasSchedules = _schedules.isNotEmpty;
        final hasLimit = _dailyLimit.enabled && _dailyLimit.limitMinutes != null;

        if (hasSchedules || hasLimit) {
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

  Future<void> _addSchedule() async {
    int startHour = 18;
    int startMin = 0;
    int endHour = 21;
    int endMin = 0;

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Add Schedule'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
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
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Add'),
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

        final id = await AndroidPlatformService.addSchedule(
          packageName: widget.packageName,
          startMinutes: startMinutes,
          endMinutes: endMinutes,
          enabled: true,
        );

        if (!mounted) return;

        if (id > 0) {
          final newSchedule = AppScheduleModel(
            id: id,
            startMinutes: startMinutes,
            endMinutes: endMinutes,
            enabled: true,
          );
          setState(() {
            _schedules = [..._schedules, newSchedule];
            _isSaving = false;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Schedule added')),
          );
        } else {
          setState(() => _isSaving = false);
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to add schedule')),
          );
        }
      } catch (e) {
        if (!mounted) return;
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error adding schedule: $e')),
        );
      }
    }
  }

  Future<void> _editSchedule(AppScheduleModel schedule) async {
    int startHour = schedule.startMinutes ~/ 60;
    int startMin = schedule.startMinutes % 60;
    int endHour = schedule.endMinutes ~/ 60;
    int endMin = schedule.endMinutes % 60;

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Edit Schedule'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
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
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
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

        final success = await AndroidPlatformService.updateSchedule(
          id: schedule.id,
          startMinutes: startMinutes,
          endMinutes: endMinutes,
          enabled: schedule.enabled,
        );

        if (!mounted) return;

        if (success) {
          setState(() {
            _schedules = _schedules.map((s) {
              if (s.id == schedule.id) {
                return s.copyWith(startMinutes: startMinutes, endMinutes: endMinutes);
              }
              return s;
            }).toList();
            _isSaving = false;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Schedule updated')),
          );
        } else {
          setState(() => _isSaving = false);
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to update schedule')),
          );
        }
      } catch (e) {
        if (!mounted) return;
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error updating schedule: $e')),
        );
      }
    }
  }

  Future<void> _deleteSchedule(AppScheduleModel schedule) async {
    if (!mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Schedule'),
        content: Text('Delete schedule ${schedule.displayString()}?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        setState(() => _isSaving = true);

        final success = await AndroidPlatformService.deleteSchedule(schedule.id);

        if (!mounted) return;

        if (success) {
          setState(() {
            _schedules = _schedules.where((s) => s.id != schedule.id).toList();
            _isSaving = false;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Schedule deleted')),
          );
        } else {
          setState(() => _isSaving = false);
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to delete schedule')),
          );
        }
      } catch (e) {
        if (!mounted) return;
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error deleting schedule: $e')),
        );
      }
    }
  }

  Future<void> _toggleSchedule(AppScheduleModel schedule) async {
    final newEnabled = !schedule.enabled;
    try {
      final success = await AndroidPlatformService.toggleScheduleEnabled(
        schedule.id,
        newEnabled,
      );

      if (!mounted) return;

      if (success) {
        setState(() {
          _schedules = _schedules.map((s) {
            if (s.id == schedule.id) {
              return s.copyWith(enabled: newEnabled);
            }
            return s;
          }).toList();
        });
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error toggling schedule: $e')),
      );
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

                    // --- Schedules Section ---
                    Card(
                      margin: const EdgeInsets.symmetric(horizontal: 16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          ListTile(
                            leading: const Icon(Icons.schedule),
                            title: const Text('Schedules'),
                            subtitle: Text(
                              _schedules.isEmpty
                                  ? 'No blocking schedules'
                                  : '${_schedules.length} schedule${_schedules.length == 1 ? '' : 's'} configured',
                            ),
                          ),
                          if (_schedules.isNotEmpty)
                            ..._schedules.map((schedule) => Column(
                              children: [
                                const Divider(height: 1),
                                ListTile(
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                                  leading: Icon(
                                    schedule.enabled ? Icons.access_time : Icons.access_time_filled_outlined,
                                    color: schedule.enabled ? Colors.orange : Colors.grey,
                                  ),
                                  title: Text(
                                    schedule.displayString(),
                                    style: TextStyle(
                                      decoration: schedule.enabled ? null : TextDecoration.lineThrough,
                                      color: schedule.enabled ? null : Colors.grey,
                                    ),
                                  ),
                                  subtitle: Text(
                                    schedule.enabled ? 'Active' : 'Disabled',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: schedule.enabled ? Colors.green : Colors.grey,
                                    ),
                                  ),
                                  trailing: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Switch(
                                        value: schedule.enabled,
                                        onChanged: (_) => _toggleSchedule(schedule),
                                      ),
                                      PopupMenuButton<String>(
                                        onSelected: (value) {
                                          if (value == 'edit') {
                                            _editSchedule(schedule);
                                          } else if (value == 'delete') {
                                            _deleteSchedule(schedule);
                                          }
                                        },
                                        itemBuilder: (context) => [
                                          const PopupMenuItem(
                                            value: 'edit',
                                            child: Row(
                                              children: [
                                                Icon(Icons.edit, size: 20),
                                                SizedBox(width: 8),
                                                Text('Edit'),
                                              ],
                                            ),
                                          ),
                                          const PopupMenuItem(
                                            value: 'delete',
                                            child: Row(
                                              children: [
                                                Icon(Icons.delete, size: 20, color: Colors.red),
                                                SizedBox(width: 8),
                                                Text('Delete', style: TextStyle(color: Colors.red)),
                                              ],
                                            ),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            )),
                          Padding(
                            padding: const EdgeInsets.all(8),
                            child: SizedBox(
                              width: double.infinity,
                              child: TextButton.icon(
                                onPressed: _addSchedule,
                                icon: const Icon(Icons.add),
                                label: const Text('Add Schedule'),
                              ),
                            ),
                          ),
                        ],
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
