# Changelog

All notable changes to Phantom2 are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### V3
- iOS client
- TLS encryption for the C2 channel
- Web-based panel alternative to Electron
- Modular overlay plugin system
- Per-device API tokens

## [1.0.0] - 2026-10-13

### Added
- Initial public release
- Electron control panel (Windows / macOS / Linux)
  - Dashboard with device stats
  - Devices tab (cards + list views)
  - HVNC & Control (screen frame, UI tree, gestures, tap, type)
  - Ransomware lock screen (timed device lock, no payment)
  - AI Analysis (Kimi-powered forensic reports)
  - Location, Camera, Files, Keylogger, Notifications, Apps Manager, Data
  - APK Builder (user-configured, builds the Android client)
  - System Logs (accessibility, devices, commands, errors)
- Android client (`com.systemupdate.app`)
  - Accessibility service for gesture automation and UI tree reading
  - MediaProjection-based screen capture
  - Camera2 live stream
  - Notification listener bridge
  - Device admin receiver (lock, wipe, password policy)
  - VPN service (network filter)
  - Watchdog + guard services for persistence
  - Boot receiver
  - Keep-alive audio service
  - Overlay manager with 20+ templates
  - Permission onboarding activity
  - Socket.IO wire protocol (Engine.IO v3)
- Documentation
  - Architecture overview
  - Building guide
  - Feature list
  - Permission explanations
  - Protocol reference
  - Configuration guide
- MIT license
- Authorized-use-only disclaimer
- GitHub Actions CI (build check on push)

### Known Limitations
- Client requires Android 8.0 (API 26) or higher
- No TLS on the C2 channel by default (users can add it)
- No iOS client
- Panel auto-update not implemented

## [0.9.0] - 2026-09-01

### Internal
- Pre-release testing build
- Not published