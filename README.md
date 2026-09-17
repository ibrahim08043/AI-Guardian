# AI Guardian

A personal digital wellness companion Android application with local AI capabilities.

## Architecture

This application follows a **Flutter-first UI with Kotlin native enforcement** architecture:

```
┌─────────────────────────────────────────┐
│           Flutter UI Layer              │
│  ┌─────────┐ ┌──────────┐ ┌─────────┐  │
│  │Dashboard│ │Restrictions│ │  Coach  │  │
│  └─────────┘ └──────────┘ └─────────┘  │
└─────────────────────────────────────────┘
                    │
            MethodChannel/EventChannel
                    │
┌─────────────────────────────────────────┐
│         Kotlin Native Layer             │
│  ┌─────────────┐  ┌─────────────────┐   │
│  │Accessibility│  │ Policy Engine   │   │
│  │  Service    │→ │ (BLOCK/ALLOW)  │   │
│  └─────────────┘  └─────────────────┘   │
└─────────────────────────────────────────┘
```

**Critical Rule:** Flutter is NOT on the restriction enforcement path. The Kotlin Policy Engine handles blocking directly.

## Project Structure

```
lib/
├── app/
│   └── router.dart              # GoRouter configuration
├── core/
│   ├── constants/
│   │   └── app_constants.dart   # App-wide constants
│   ├── theme/
│   │   └── app_theme.dart       # Material 3 theme
│   ├── utils/
│   │   └── app_utils.dart       # Utility functions
│   └── widgets/
│       └── main_scaffold.dart   # Bottom navigation
├── features/
│   ├── analytics/screens/       # Analytics dashboard
│   ├── coach/screens/           # AI wellness coach
│   ├── dashboard/screens/       # Main dashboard
│   ├── onboarding/screens/      # First-run experience
│   ├── restrictions/screens/    # App blocking rules
│   └── settings/screens/        # App configuration
├── platform/
│   └── platform_channels.dart   # Flutter <-> Kotlin communication
└── main.dart                    # App entry point
```

## Getting Started

### Prerequisites

- Flutter SDK 3.2.0 or higher
- Android Studio or VS Code with Flutter extension
- Android device or emulator (API level 24+)

### Installation

1. Clone the repository
2. Run `flutter pub get`
3. Run `flutter analyze` to verify no issues
4. Run `flutter run` to start the app

### Building

```bash
# Debug build
flutter build apk --debug

# Release build
flutter build apk --release
```

## Dependencies

- `flutter` - UI framework
- `cupertino_icons` - iOS-style icons
- `go_router` - Declarative routing

## Development

This is a personal-use, local-only Android application. It will never be published to the Play Store.

### Phase 1 (Current)
- [x] Flutter project foundation
- [x] Feature-first architecture
- [x] Material 3 theme
- [x] GoRouter navigation
- [x] Placeholder screens
- [x] Dashboard with mock data

### Phase 2 (Next)
- [ ] AccessibilityService implementation
- [ ] Foreground app detection
- [ ] Policy engine
- [ ] Local database (SQLite/Isar)

### Phase 3 (Future)
- [ ] Local AI runtime integration
- [ ] TTS/STT services
- [ ] Advanced analytics
- [ ] Website filtering

## License

Personal use only. Not for distribution.
