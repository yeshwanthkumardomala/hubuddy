# Hubuddy

## Hubuddy DeskHub — SIDYA Edition

An Android desk dashboard for a Windows 11 PC, built for the Vivo 1902 and compatible with any Android 5.0+ phone (API 21+).
iPhones and other devices can use the bridge's web dashboard in a browser.
It is adapted from [DeskHub](https://github.com/zacharyinserra/deskhub) (MIT).

* **App:** [`android/deskhub`](android/deskhub). Native Kotlin, package `com.sidya.deskhub`.
  PIN-locked dashboard with PC monitor, Claude Code usage and alerts, media and shortcut
  controls, calendar, weather, network and GitHub. Works offline with the last saved data.
* **PC bridge:** [`companion-bridge`](companion-bridge). Reuses the DeskHub companion and
  serves an authenticated local API on port 8765, over USB (`adb reverse`) or LAN. It also has
  an optional web dashboard.

Build the APK on Windows:

```powershell
cd android\deskhub
.\build.ps1
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Documentation: [Architecture](docs/deskhub/ARCHITECTURE.md) ·
[Build instructions](docs/deskhub/BUILD_INSTRUCTIONS.md) ·
[Companion & API setup](docs/deskhub/API_SETUP.md) ·
[Test report](docs/deskhub/TEST_REPORT.md) ·
[Release notes](docs/deskhub/RELEASE_NOTES.md) ·
[Screenshots](docs/deskhub/SCREENSHOTS.md) ·
[Third-party notices](docs/deskhub/THIRD_PARTY_NOTICES.md)
