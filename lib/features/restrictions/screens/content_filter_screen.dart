import 'package:flutter/material.dart';
import '../../../platform/android_platform_service.dart';

/// Content filter screen — manage blocked words/phrases.
///
/// Shows a list of content filter rules with add, edit, delete,
/// and toggle capabilities. Includes a master switch to enable/disable
/// all content filtering at once.
///
/// All data is stored locally via SQLite — no network calls.
class ContentFilterScreen extends StatefulWidget {
  const ContentFilterScreen({super.key});

  @override
  State<ContentFilterScreen> createState() => _ContentFilterScreenState();
}

class _ContentFilterScreenState extends State<ContentFilterScreen> {
  List<Map<String, dynamic>> _rules = [];
  bool _isLoading = true;
  bool _masterEnabled = true;

  @override
  void initState() {
    super.initState();
    _loadRules();
  }

  Future<void> _loadRules() async {
    debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_START');
    if (!mounted) {
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_ABORT not mounted');
      return;
    }
    setState(() => _isLoading = true);
    try {
      final rules = await AndroidPlatformService.getAllContentFilterRules();
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_RESULT count=${rules.length}');
      for (final r in rules) {
        debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG]   rule id=${r['id']} (type=${r['id'].runtimeType}) phrase=${r['phrase']} enabled=${r['enabled']} (type=${r['enabled'].runtimeType}) matchMode=${r['matchMode']}');
      }
      final masterEnabled =
          await AndroidPlatformService.isMasterContentFilterEnabled();
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_MASTER masterEnabled=$masterEnabled');
      if (!mounted) {
        debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_ABORT not mounted after await');
        return;
      }
      setState(() {
        _rules = rules;
        _masterEnabled = masterEnabled;
        _isLoading = false;
      });
    } catch (e) {
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_LOAD_FAILED error=$e');
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _rules = [];
      });
    }
  }

  Future<void> _toggleMaster(bool value) async {
    debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_TOGGLE_MASTER value=$value');
    try {
      await AndroidPlatformService.setMasterContentFilterEnabled(value);
      if (!mounted) return;
      setState(() => _masterEnabled = value);
    } catch (e) {
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_TOGGLE_MASTER_FAILED error=$e');
    }
  }

  Future<void> _toggleRule(int id, bool currentEnabled) async {
    final newEnabled = !currentEnabled;
    debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_TOGGLE_START id=$id currentEnabled=$currentEnabled newEnabled=$newEnabled idType=${id.runtimeType}');
    try {
      final success = await AndroidPlatformService.setContentFilterRuleEnabled(
          id, newEnabled);
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_TOGGLE_RESULT success=$success');
      await _loadRules();
    } catch (e) {
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_TOGGLE_FAILED id=$id error=$e');
    }
  }

  Future<void> _deleteRule(int id) async {
    debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_DELETE_START id=$id idType=${id.runtimeType}');
    if (!mounted) {
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_DELETE_ABORT not mounted before dialog');
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Rule'),
        content: const Text(
            'Are you sure you want to delete this content filter rule?'),
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

    debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_DELETE_DIALOG confirmed=$confirmed');

    if (confirmed == true) {
      try {
        final success = await AndroidPlatformService.deleteContentFilterRule(id);
        debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_DELETE_RESULT id=$id success=$success');
        await _loadRules();
      } catch (e) {
        debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_DELETE_FAILED id=$id error=$e');
      }
    }
  }

  Future<void> _showAddRuleDialog() async {
    final phraseController = TextEditingController();
    bool exactMode = false;

    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('Add Blocked Word/Phrase'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: phraseController,
                autofocus: true,
                decoration: const InputDecoration(
                  labelText: 'Word or phrase',
                  hintText: 'e.g. gambling, explicit content',
                  border: OutlineInputBorder(),
                ),
                textInputAction: TextInputAction.done,
                onSubmitted: (_) {
                  if (phraseController.text.trim().isNotEmpty) {
                    Navigator.of(ctx).pop(true);
                  }
                },
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  const Icon(Icons.info_outline, size: 16),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'Match mode:',
                      style: Theme.of(ctx).textTheme.bodyMedium,
                    ),
                  ),
                  ChoiceChip(
                    label: const Text('Contains'),
                    selected: !exactMode,
                    onSelected: (_) => setDialogState(() => exactMode = false),
                  ),
                  const SizedBox(width: 8),
                  ChoiceChip(
                    label: const Text('Exact'),
                    selected: exactMode,
                    onSelected: (_) => setDialogState(() => exactMode = true),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                exactMode
                    ? 'Blocks when text exactly matches the phrase'
                    : 'Blocks when text contains the phrase',
                style: Theme.of(ctx).textTheme.bodySmall?.copyWith(
                      color: Theme.of(ctx).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () {
                if (phraseController.text.trim().isNotEmpty) {
                  Navigator.of(ctx).pop(true);
                }
              },
              child: const Text('Add'),
            ),
          ],
        ),
      ),
    );

    if (result == true && phraseController.text.trim().isNotEmpty) {
      final mode = exactMode ? 'EXACT' : 'CONTAINS';
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_ADD_START phrase=${phraseController.text.trim()} mode=$mode');
      final id = await AndroidPlatformService.saveContentFilterRule(
        phrase: phraseController.text.trim(),
        matchMode: mode,
      );
      debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] DART_ADD_RESULT id=$id');
      if (id > 0 && mounted) {
        await _loadRules();
      }
    }

    phraseController.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    return Column(
      children: [
        // Master switch
        SwitchListTile(
          title: const Text('Content Filtering'),
          subtitle: Text(
            _masterEnabled
                ? 'Monitoring text for blocked words/phrases'
                : 'Content filtering is disabled',
          ),
          value: _masterEnabled,
          onChanged: _toggleMaster,
        ),
        const Divider(height: 1),

        // Rule count
        if (_rules.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Row(
              children: [
                Text(
                  'BLOCKED WORDS (${_rules.length})',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
              ],
            ),
          ),

        // Rules list
        if (_rules.isEmpty)
          Expanded(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.filter_alt_off,
                    size: 48,
                    color: Theme.of(context)
                        .colorScheme
                        .onSurfaceVariant
                        .withValues(alpha: 0.5),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'No blocked words or phrases',
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Tap the + button to add a word or phrase to block',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ],
              ),
            ),
          )
        else
          Expanded(
            child: ListView.builder(
              itemCount: _rules.length,
              itemBuilder: (context, index) {
                final rule = _rules[index];
                final phrase = rule['phrase'] as String? ?? '';
                // Handle enabled being bool, int (0/1), or null
                final rawEnabled = rule['enabled'];
                final enabled = rawEnabled == true || rawEnabled == 1;
                final matchMode = rule['matchMode'] as String? ?? 'CONTAINS';
                // Handle id being int, long, or num
                final rawId = rule['id'];
                final int id = rawId is int ? rawId : (rawId is num ? rawId.toInt() : 0);

                return Card(
                  margin:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  child: ListTile(
                    leading: Icon(
                      enabled ? Icons.filter_alt : Icons.filter_alt_off,
                      color: enabled
                          ? Theme.of(context).colorScheme.primary
                          : Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                    title: Text(
                      phrase,
                      style: TextStyle(
                        fontWeight: FontWeight.w600,
                        decoration: enabled ? null : TextDecoration.lineThrough,
                        color: enabled
                            ? null
                            : Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    subtitle: Text(
                      matchMode == 'EXACT' ? 'Exact match' : 'Contains',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Switch(
                          value: enabled,
                          onChanged: (bool newValue) {
                            debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] SWITCH_ONCHANGED id=$id oldEnabled=$enabled newRequested=$newValue');
                            _toggleRule(id, enabled);
                          },
                        ),
                        PopupMenuButton<String>(
                          onSelected: (value) {
                            debugPrint('[AI_GUARDIAN_CONTENT_FILTER_DEBUG] POPUP_SELECTED value=$value id=$id');
                            if (value == 'delete') {
                              _deleteRule(id);
                            }
                          },
                          itemBuilder: (context) => [
                            const PopupMenuItem(
                              value: 'delete',
                              child: Row(
                                children: [
                                  Icon(Icons.delete, size: 20),
                                  SizedBox(width: 8),
                                  Text('Delete'),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),

        // Add button
        if (_masterEnabled)
          Padding(
            padding: const EdgeInsets.all(16),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _showAddRuleDialog,
                icon: const Icon(Icons.add),
                label: const Text('Add Blocked Word/Phrase'),
              ),
            ),
          ),
      ],
    );
  }
}
