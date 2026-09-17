import 'package:flutter/material.dart';
import '../../../platform/android_platform_service.dart';
import '../../../platform/models/policy_model.dart';

/// Policy management section for the Settings screen.
/// Allows users to view, add, edit, and delete app policies.
class PolicyManagementSection extends StatefulWidget {
  const PolicyManagementSection({super.key});

  @override
  State<PolicyManagementSection> createState() => _PolicyManagementSectionState();
}

class _PolicyManagementSectionState extends State<PolicyManagementSection> {
  List<PolicyModel> policies = [];
  bool isLoading = true;
  String? errorMessage;

  @override
  void initState() {
    super.initState();
    _loadPolicies();
  }

  Future<void> _loadPolicies() async {
    try {
      setState(() {
        isLoading = true;
        errorMessage = null;
      });

      final loadedPolicies = await AndroidPlatformService.getPolicies();

      if (!mounted) return;

      setState(() {
        policies = loadedPolicies;
        isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        errorMessage = 'Failed to load policies: $e';
        isLoading = false;
      });
      debugPrint('[PolicyUI] Error loading policies: $e');
    }
  }

  Future<void> _showAddPolicyDialog() async {
    final packageNameController = TextEditingController();
    PolicyActionModel selectedAction = PolicyActionModel.allow;
    bool enabled = true;

    if (!mounted) return;

    final result = await showDialog<PolicyModel?>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Add App Policy'),
        content: StatefulBuilder(
          builder: (context, setState) => SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: packageNameController,
                  decoration: const InputDecoration(
                    labelText: 'Package Name',
                    hintText: 'e.g., com.google.android.youtube',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                RadioListTile<PolicyActionModel>(
                  title: const Text('Allow'),
                  value: PolicyActionModel.allow,
                  groupValue: selectedAction,
                  onChanged: (value) {
                    if (value != null) {
                      setState(() => selectedAction = value);
                    }
                  },
                ),
                RadioListTile<PolicyActionModel>(
                  title: const Text('Block'),
                  value: PolicyActionModel.block,
                  groupValue: selectedAction,
                  onChanged: (value) {
                    if (value != null) {
                      setState(() => selectedAction = value);
                    }
                  },
                ),
                SwitchListTile(
                  title: const Text('Enabled'),
                  value: enabled,
                  onChanged: (value) {
                    setState(() => enabled = value);
                  },
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              if (packageNameController.text.trim().isNotEmpty) {
                final policy = PolicyModel(
                  packageName: packageNameController.text.trim(),
                  action: selectedAction,
                  enabled: enabled,
                );
                Navigator.pop(context, policy);
              }
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );

    if (result != null) {
      try {
        await AndroidPlatformService.savePolicy(result);
        _loadPolicies();
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Failed to save policy: $e')),
          );
        }
        debugPrint('[PolicyUI] Error saving policy: $e');
      }
    }
  }

  Future<void> _showEditPolicyDialog(PolicyModel policy) async {
    PolicyActionModel selectedAction = policy.action;
    bool enabled = policy.enabled;

    if (!mounted) return;

    final result = await showDialog<PolicyModel?>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Edit: ${policy.packageName}'),
        content: StatefulBuilder(
          builder: (context, setState) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Package: ${policy.packageName}'),
              const SizedBox(height: 16),
              RadioListTile<PolicyActionModel>(
                title: const Text('Allow'),
                value: PolicyActionModel.allow,
                groupValue: selectedAction,
                onChanged: (value) {
                  if (value != null) {
                    setState(() => selectedAction = value);
                  }
                },
              ),
              RadioListTile<PolicyActionModel>(
                title: const Text('Block'),
                value: PolicyActionModel.block,
                groupValue: selectedAction,
                onChanged: (value) {
                  if (value != null) {
                    setState(() => selectedAction = value);
                  }
                },
              ),
              SwitchListTile(
                title: const Text('Enabled'),
                value: enabled,
                onChanged: (value) {
                  setState(() => enabled = value);
                },
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              final updated = PolicyModel(
                packageName: policy.packageName,
                action: selectedAction,
                enabled: enabled,
              );
              Navigator.pop(context, updated);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );

    if (result != null) {
      try {
        await AndroidPlatformService.updatePolicy(result);
        _loadPolicies();
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Failed to update policy: $e')),
          );
        }
        debugPrint('[PolicyUI] Error updating policy: $e');
      }
    }
  }

  Future<void> _showDeleteConfirmation(PolicyModel policy) async {
    if (!mounted) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete Policy'),
        content: Text('Delete policy for ${policy.packageName}?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        await AndroidPlatformService.deletePolicy(policy.packageName);
        _loadPolicies();
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Failed to delete policy: $e')),
          );
        }
        debugPrint('[PolicyUI] Error deleting policy: $e');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              const Text(
                'App Policies',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              const Spacer(),
              FloatingActionButton.small(
                onPressed: _showAddPolicyDialog,
                tooltip: 'Add Policy',
                child: const Icon(Icons.add),
              ),
            ],
          ),
        ),
        if (isLoading)
          const Padding(
            padding: EdgeInsets.all(16),
            child: CircularProgressIndicator(),
          )
        else if (errorMessage != null)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                Text('Error: $errorMessage', style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                )),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: _loadPolicies,
                  child: const Text('Retry'),
                ),
              ],
            ),
          )
        else if (policies.isEmpty)
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text('No policies configured. Tap + to add one.'),
          )
        else
          Card(
            child: ListView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: policies.length,
              itemBuilder: (context, index) {
                final policy = policies[index];
                return Column(
                  children: [
                    ListTile(
                      title: Text(policy.packageName),
                      subtitle: Text(
                        '${policy.action.displayName()} - ${policy.enabled ? 'Enabled' : 'Disabled'}',
                      ),
                      trailing: SizedBox(
                        width: 100,
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.end,
                          children: [
                            IconButton(
                              icon: const Icon(Icons.edit),
                              tooltip: 'Edit',
                              onPressed: () => _showEditPolicyDialog(policy),
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete),
                              tooltip: 'Delete',
                              onPressed: () => _showDeleteConfirmation(policy),
                            ),
                          ],
                        ),
                      ),
                    ),
                    if (index < policies.length - 1)
                      const Divider(height: 1),
                  ],
                );
              },
            ),
          ),
      ],
    );
  }
}
