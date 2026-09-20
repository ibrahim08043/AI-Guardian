import 'package:flutter/material.dart';

import 'password_service.dart';
import 'password_screen.dart';

/// A widget that gates access to its child based on password authentication.
///
/// On mount, checks whether authentication is required. If so, displays
/// [PasswordScreen] instead of [child]. Once the password is verified,
/// the protected content is shown.
///
/// The password is read from Android assets (app_password.txt) and verified
/// on the native Kotlin side. The password content is never stored in
/// SharedPreferences, logs, or any persistent location.
class AuthGate extends StatefulWidget {
  final Widget child;

  const AuthGate({super.key, required this.child});

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  /// Session-level flag: once authenticated, remain authenticated until
  /// the app is restarted. This prevents re-prompting on every tab switch.
  static bool _sessionAuthenticated = false;

  bool _isAuthenticated = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _checkAuth();
  }

  Future<void> _checkAuth() async {
    // If already authenticated in this session, skip the check.
    if (_sessionAuthenticated) {
      setState(() {
        _isAuthenticated = true;
        _isLoading = false;
      });
      return;
    }

    // Check if a password is configured. If no password exists, grant access.
    // If a password exists, require authentication before showing content.
    try {
      final passwordExists = await PasswordService.hasPassword();
      if (!mounted) return;

      setState(() {
        // No password configured → grant access immediately.
        // Password configured → require authentication (show password screen).
        _isAuthenticated = !passwordExists;
        _isLoading = false;
      });
    } catch (e) {
      // On error, require authentication (fail-closed).
      if (!mounted) return;
      setState(() {
        _isAuthenticated = false;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(
        body: Center(
          child: CircularProgressIndicator(),
        ),
      );
    }

    if (_isAuthenticated) {
      return widget.child;
    }

    return PasswordScreen(
      onVerified: () {
        _sessionAuthenticated = true;
        setState(() {
          _isAuthenticated = true;
        });
      },
    );
  }
}
