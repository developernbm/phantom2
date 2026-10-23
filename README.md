 HEAD
# Phantom2

**Open-source Android remote administration framework.**
**Built by NBM DEV, in collaboration with BTDs.**

Phantom2 is a complete C2 suite for authorized Android security testing — a
cross-platform Electron control panel paired with a lightweight Android
client. Use it for red-team engagements, lab research, and testing on devices
you own.

Phantom 3 and Phantom 4 are available with production-grade features and
advanced capabilities. Phantom2 is the public, MIT-licensed baseline that
anyone can clone, build, and extend.

---

## ⚠️ Authorized Use Only

Phantom2 is a remote administration tool. Using it against devices you do
not own, or lack explicit permission to test, is **illegal** in most
jurisdictions. See [DISCLAIMER.md](DISCLAIMER.md).

You are solely responsible for how you use this software.

---

## What's inside

| Component | Language | Purpose |
|---|---|---|
| **Phantom2 Panel** | Electron / Node.js | Operator console — manage devices, run commands, view screen, harvest data, run AI analysis |
| **System Update** | Android (Java) | Client APK — connects back to the panel, executes commands, reports state |

Everything is MIT licensed. Everything is source-available.

---

## Panel features

- **Dashboard** — live stats, connected device count, activity feed
- **Devices** — card + list views, per-device status, a11y + admin state, battery, carrier, country
- **HVNC & Control** — live screen frames, UI tree overlay, hitboxes, tap, type, swipe, key codes, navigation
- **Ransomware Lock** — timed device lock with fake encryption animation (no payment screen)
- **AI Analysis** — Kimi/Moonshot-powered forensic reports over harvested data, streaming Markdown output
- **Location** — live coordinates, accuracy, Google Maps embed
- **Camera** — front + back live JPEG stream
- **Files** — browse, list, pull files from the device
- **Keylogger** — live keystroke stream
- **Notifications** — live notification capture
- **Apps Manager** — installed apps, open/kill actions
- **Data** — SMS, contacts, calls, clipboard, keylogs
- **APK Builder** — build the client APK from source with your C2 baked in
- **Dropper Server** — optional HTTP server for APK sideloading
- **System Logs** — accessibility, devices, commands, errors

---

## Client features

- **Accessibility Service** — gesture automation, UI tree parsing, key event injection
- **Screen Capture** — MediaProjection + VirtualDisplay pipeline
- **Camera Stream** — Camera2 live stream at 5 fps
- **Notification Listener** — full notification access
- **Device Admin** — lock, wipe, password policy
- **Overlay Engine** — 20+ templates: system updates, loading screens, BSOD, kernel panic, bank login, PIN entry, passive capture, black VNC, and more
- **Ransomware Lock Screen** — timed device lock with encrypting-files animation
- **Persistence** — boot receiver, watchdog + guard services (separate processes), alarm manager, keep-alive audio
- **Clipboard Monitor** — captures all clipboard changes
- **Keylogger** — via accessibility text-changed events
- **Location Tracker** — GPS + network provider
- **File Browser** — list, pull files
- **Permission Onboarding** — honest checklist that explains every permission

---

## Requirements

**Panel:**
- Node.js 20+
- Windows 10/11, macOS 12+, or modern Linux

**Client build:**
- JDK 17
- Android SDK 34 (build-tools 34.0.0 https://developer.android.com/studio
- Android device running 8.0 (API 26) or higher

---

## Quick Start

### 1. Clone

```bash
git clone https://github.com/developernbm/phantom2.git
cd phantom2
npm install
npm start

# PHANTOM2
 2a3b2a8b57da9c3d02c6cf2ac1cf11ef51d6bef1
