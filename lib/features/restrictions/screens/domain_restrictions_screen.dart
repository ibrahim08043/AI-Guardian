import 'package:flutter/material.dart';
import '../../../platform/android_platform_service.dart';
import '../../../platform/models/domain_model.dart';

/// Domain / Website restrictions screen.
/// Allows users to manage a list of blocked domains.
class DomainRestrictionsScreen extends StatefulWidget {
  const DomainRestrictionsScreen({super.key});

  @override
  State<DomainRestrictionsScreen> createState() =>
      _DomainRestrictionsScreenState();
}

class _DomainRestrictionsScreenState extends State<DomainRestrictionsScreen> {
  List<DomainModel> domains = [];
  bool isLoading = true;
  String? errorMessage;
  bool isVpnActive = false;

  final TextEditingController _domainController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadDomains();
  }

  @override
  void dispose() {
    _domainController.dispose();
    super.dispose();
  }

  /// Normalize domain input on the Flutter side for display.
  /// The Kotlin side does the authoritative normalization via DomainPolicy.normalize().
  String _normalizeForDisplay(String input) {
    var domain = input.trim().toLowerCase();
    // Remove protocol
    domain = domain.replaceFirst(RegExp(r'^https?://'), '');
    // Remove path
    final slashIndex = domain.indexOf('/');
    if (slashIndex >= 0) domain = domain.substring(0, slashIndex);
    // Remove port
    final colonIndex = domain.indexOf(':');
    if (colonIndex >= 0) domain = domain.substring(0, colonIndex);
    // Remove trailing dot
    if (domain.endsWith('.')) domain = domain.substring(0, domain.length - 1);
    // Remove www. prefix
    if (domain.startsWith('www.')) domain = domain.substring(4);
    return domain;
  }

  Future<void> _loadDomains() async {
    try {
      setState(() {
        isLoading = true;
        errorMessage = null;
      });

      final rawDomains = await AndroidPlatformService.getAllDomains();
      final loadedDomains =
          rawDomains.map((m) => DomainModel.fromMap(m)).toList();

      if (mounted) {
        setState(() {
          domains = loadedDomains;
          isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          isLoading = false;
          errorMessage = 'Error loading domains: $e';
        });
      }
    }
  }

  Future<void> _addDomain() async {
    final input = _domainController.text.trim();
    if (input.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Enter a domain')),
      );
      return;
    }

    // Pre-validate: must contain a dot
    final normalized = _normalizeForDisplay(input);
    if (!normalized.contains('.') || normalized.startsWith('.') || normalized.endsWith('.')) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Invalid domain format. Example: youtube.com')),
        );
      }
      return;
    }

    try {
      setState(() => isLoading = true);
      final success = await AndroidPlatformService.saveDomain(
        domain: input,
        enabled: true,
      );

      _domainController.clear();

      if (!mounted) return;

      if (success) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Domain added')),
        );
        await _loadDomains();
        await _updateVpnIfNeeded();
      } else {
        setState(() {
          isLoading = false;
          errorMessage = 'Invalid domain format';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => isLoading = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: $e')),
        );
      }
    }
  }

  Future<void> _deleteDomain(String domain) async {
    try {
      final success = await AndroidPlatformService.deleteDomain(domain);
      if (success) {
        await _loadDomains();
        await _updateVpnIfNeeded();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: $e')),
        );
      }
    }
  }

  Future<void> _toggleDomainEnabled(DomainModel domain) async {
    try {
      final success = await AndroidPlatformService.setDomainEnabled(
          domain.domain, !domain.enabled);
      if (success) {
        await _loadDomains();
        await _updateVpnIfNeeded();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: $e')),
        );
      }
    }
  }

  Future<void> _updateVpnIfNeeded() async {
    final enabledCount = domains.where((d) => d.enabled).length;
    if (enabledCount > 0 && !isVpnActive) {
      final success = await AndroidPlatformService.startDomainBlocking();
      if (mounted) {
        setState(() => isVpnActive = success);
        if (!success) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('VPN permission required. Tap "Start Blocking" to grant it.'),
              duration: Duration(seconds: 4),
            ),
          );
        }
      }
    } else if (enabledCount == 0 && isVpnActive) {
      await AndroidPlatformService.stopDomainBlocking();
      if (mounted) {
        setState(() => isVpnActive = false);
      }
    }
  }

  Future<void> _toggleVpn() async {
    if (isVpnActive) {
      await AndroidPlatformService.stopDomainBlocking();
      if (mounted) {
        setState(() => isVpnActive = false);
      }
    } else {
      final success = await AndroidPlatformService.startDomainBlocking();
      if (mounted) {
        setState(() => isVpnActive = success);
        if (!success) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('VPN permission denied or failed. Please grant VPN permission when prompted.'),
              duration: Duration(seconds: 4),
            ),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Websites')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    final enabledDomains = domains.where((d) => d.enabled).toList();
    final disabledDomains = domains.where((d) => !d.enabled).toList();

    return Scaffold(
      appBar: AppBar(title: const Text('Websites')),
      body: Column(
        children: [
          // VPN status banner
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            color: isVpnActive
                ? Colors.green.shade50
                : Colors.orange.shade50,
            child: Row(
              children: [
                Icon(
                  isVpnActive ? Icons.shield : Icons.shield_outlined,
                  color: isVpnActive ? Colors.green : Colors.orange,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    isVpnActive
                        ? 'VPN Active — DNS blocking enabled'
                        : 'VPN Inactive — blocking disabled',
                    style: TextStyle(
                      fontSize: 13,
                      color: isVpnActive
                          ? Colors.green.shade800
                          : Colors.orange.shade800,
                    ),
                  ),
                ),
                TextButton(
                  onPressed: _toggleVpn,
                  child: Text(
                    isVpnActive ? 'Stop' : 'Start Blocking',
                    style: TextStyle(
                      color: isVpnActive ? Colors.red : Colors.green,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Add a website to block',
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _domainController,
                              decoration: InputDecoration(
                                hintText: 'youtube.com',
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                contentPadding: const EdgeInsets.symmetric(
                                    horizontal: 12),
                              ),
                              onSubmitted: (_) => _addDomain(),
                            ),
                          ),
                          const SizedBox(width: 8),
                          ElevatedButton(
                            onPressed: _addDomain,
                            child: const Text('Add'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                if (errorMessage != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.red.shade50,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        errorMessage!,
                        style: TextStyle(color: Colors.red.shade700),
                      ),
                    ),
                  ),
                if (enabledDomains.isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Text(
                      'BLOCKED (${enabledDomains.length})',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ),
                  ...enabledDomains.map((domain) => _DomainTile(
                        domain: domain,
                        onToggle: () => _toggleDomainEnabled(domain),
                        onDelete: () => _deleteDomain(domain.domain),
                      )),
                ],
                if (disabledDomains.isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Text(
                      'DISABLED (${disabledDomains.length})',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: Theme.of(context)
                            .colorScheme
                            .onSurface
                            .withValues(alpha: 0.6),
                      ),
                    ),
                  ),
                  ...disabledDomains.map((domain) => _DomainTile(
                        domain: domain,
                        onToggle: () => _toggleDomainEnabled(domain),
                        onDelete: () => _deleteDomain(domain.domain),
                      )),
                ],
                if (domains.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Center(
                      child: Text(
                        'No websites blocked',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurface
                                  .withValues(alpha: 0.5),
                            ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DomainTile extends StatelessWidget {
  final DomainModel domain;
  final VoidCallback onToggle;
  final VoidCallback onDelete;

  const _DomainTile({
    required this.domain,
    required this.onToggle,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: ListTile(
        title: Text(domain.domain),
        subtitle: Text(
          domain.enabled ? 'Blocked' : 'Disabled',
          style: TextStyle(
            fontSize: 12,
            color: domain.enabled ? Colors.red : Colors.grey,
          ),
        ),
        trailing: PopupMenuButton(
          itemBuilder: (context) => [
            PopupMenuItem(
              child: Text(domain.enabled ? 'Disable' : 'Enable'),
              onTap: onToggle,
            ),
            PopupMenuItem(
              child: const Text('Delete'),
              onTap: onDelete,
            ),
          ],
        ),
      ),
    );
  }
}
