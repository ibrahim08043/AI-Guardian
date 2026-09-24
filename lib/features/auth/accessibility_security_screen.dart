import 'package:flutter/material.dart';

import 'accessibility_password_service.dart';

/// Screen for entering the Accessibility Security password.
///
/// This is a separate credential from the main app password. It gates
/// access to the Accessibility Service settings action only.
///
/// The password is read from Android assets (accessibility_password.txt)
/// and verified locally. The actual password content is never exposed to
/// the user, logs, or source code.
class AccessibilitySecurityScreen extends StatefulWidget {
  /// Called when the password is verified successfully.
  final VoidCallback onVerified;

  const AccessibilitySecurityScreen({super.key, required this.onVerified});

  @override
  State<AccessibilitySecurityScreen> createState() =>
      _AccessibilitySecurityScreenState();
}

class _AccessibilitySecurityScreenState
    extends State<AccessibilitySecurityScreen> {
  final _controller = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _verify() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    // Synchronous verification — no platform channel needed.
    final password = _controller.text;
    final isCorrect = AccessibilityPasswordService.verify(password);

    if (!mounted) return;

    if (isCorrect) {
      widget.onVerified();
    } else {
      setState(() {
        _isLoading = false;
        _errorMessage = 'Incorrect password';
      });
      _controller.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Accessibility Security'),
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.lock_outline,
                  size: 64,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(height: 24),
                Text(
                  'Accessibility Security',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  'Enter the accessibility security password to continue.',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context)
                            .colorScheme
                            .onSurfaceVariant,
                      ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 32),
                SizedBox(
                  width: 300,
                  child: TextFormField(
                    controller: _controller,
                    obscureText: true,
                    enabled: !_isLoading,
                    decoration: InputDecoration(
                      labelText: 'Password',
                      border: const OutlineInputBorder(),
                      prefixIcon: const Icon(Icons.lock),
                      errorText: _errorMessage,
                    ),
                    textInputAction: TextInputAction.go,
                    onFieldSubmitted: (_) => _verify(),
                    validator: (value) {
                      if (value == null || value.isEmpty) {
                        return 'Please enter the password';
                      }
                      return null;
                    },
                  ),
                ),
                const SizedBox(height: 24),
                SizedBox(
                  width: 300,
                  height: 48,
                  child: FilledButton(
                    onPressed: _isLoading ? null : _verify,
                    child: _isLoading
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Text('Verify'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
