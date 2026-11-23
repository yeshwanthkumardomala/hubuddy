# Test report — Hubuddy 2.0.0

Date: 2026-11-23. Environment: Linux build container, OpenJDK 21, Gradle 8.14, AGP 8.7, Kotlin 2.0, Android SDK
Platform 34, Robolectric 4.14, Python 3.13.

> **Physical phone testing: PENDING.** No phone was attached and the container cannot run an emulator, so the
> Android behaviour was tested with Robolectric on **Android 5.0, 5.1, 7.0, 8.0, 9, 11 and 14**. The Windows
> PowerShell scripts and the Windows-only agent parts were **not run** (see "Not run").

## Summary

| Suite | Result |
| --- | --- |
| Android build (debug + release APK) | ✅ successful |
| Android unit + Robolectric UI tests | ✅ **173 run, 0 failed, 0 skipped** (includes 12 screenshot renders) |
| Android Lint | ✅ 0 errors, 2 expected warnings (`InsecureBaseConfiguration`: plain HTTP to a LAN agent is allowed by design and limited in code; `UnusedAttribute`: `networkSecurityConfig` is ignored below Android 7) |
| Colour contrast in Dark, Black and Light | ✅ text 7:1, secondary text and button labels 4.5:1, accent and status colours 3:1 |
| APK inspection | ✅ `com.sidya.hubuddy`, version 2.0.0, minSdk 21, INTERNET + ACCESS_NETWORK_STATE only, no native code |
| Agent tests | ✅ **48 passed** with the optional libraries installed; ✅ 48 run (41 passed, 7 skipped for missing libraries) without them |
| Agent run end to end (Linux) | ✅ started the real program, paired with a one-time code, read live stats and network data, 401 without a token |
| Laptop agent download (zip) | ✅ rebuilt twice with identical checksums; unzipped into a fresh folder and run from there: paired, live stats and network readings, web dashboard served; no old-name hits; start scripts have Windows line endings |
| Web dashboard in headless Chromium | ✅ pairs and renders; phone-width layout without horizontal scroll |

## Android tests

* **Authentication (AuthManager, PinHasher):** login, wrong PIN, lockout and its growth, forced PIN change, weak PIN
  refusal, persistence across restart, inactivity timeout, logout, PBKDF2 checked against RFC 7914 and the JDK.
* **Connectivity (DashboardRepository, AddressValidator):** against a real local HTTP server: pairing, timeout,
  reconnect after an outage, bad token, public or invalid addresses refused, offline cache after a restart, back-off.
* **Several PCs (PcRegistry, MultiPcUi):** up to 8, separate tokens, three PCs polled together with one dead,
  All PCs tiles, selecting a PC, adding/editing/removing in Settings.
* **UI flows on Android 5.0 to 14:** login, PIN change, five tabs, secondary pages, offline state, live data on every
  tab, malformed data on every tab without a crash, settings validation, rotation, theme switching, and
  portrait vs landscape layout (bottom bar and 1 column vs side rail and 2 columns).
* **Dock mode:** starts after idle time, also on battery but not at 12%, stops itself at 10%, rotates through pages,
  jumps to Claude alerts, night dim clock, touch leaves, expired session needs the PIN, malformed data.
* **Android 5.x without a Keystore:** secrets stay in memory and are never written to preferences.

## Agent tests (48)

* **System:** real readings, GPU parsing and absence, GPU asked at a sensible rate.
* **Claude Code:** token totals, duplicate lines, model split, 5-hour blocks, incremental reading with a half-written
  line, expired limits dropped, hook states and alerts, dismissal, expiry, path-character safety, status line
  recording and chaining, settings install/uninstall (backup, existing hooks kept, no duplicates, bad JSON refused).
* **Calendar:** repeating and all-day events, a failing feed skipped, local files. **GitHub:** summary, streaks, errors.
* **Network:** latency, jitter, loss, real TCP probes, persisted counters, speed test against a local server.
* **Shortcuts:** validation of every action, dispatch, no shell use, button limit. **Media:** session mapping and commands with fakes.
* **Server:** pairing, token hashing, rate limits, command validation, malformed bodies, path traversal, peer filtering.
* These tests found and fixed real bugs: app names like "Spotify.exe" came out as "exe", and Windows project paths were not split on Linux.

## Not run / pending

1. **A physical phone** (install, Keystore on the device, real USB and Wi-Fi connection, screen size, battery behaviour,
   whether dock mode starts as expected after 5 idle minutes).
2. **Android emulator / instrumentation tests:** not possible without KVM here.
3. **`build.ps1` and `start-agent.ps1`:** written for Windows PowerShell 5.1 and 7 but never executed (PowerShell could not be installed here).
4. **Windows-only agent parts:** media through `winrt`, key presses, locking and volume, reading a real NVIDIA GPU,
   and the whole agent on Windows. They were written carefully and unit-tested with fakes only.
5. **Claude Code hooks against a real Claude Code:** tested with the hook and status-line JSON as Claude Code documents it,
   not with a live session.
6. **Release APK at runtime:** it builds, but the tests ran against the unshrunk debug build.
7. **Android 5.0 to 7.0 HTTPS** to weather and GitHub may fail on old certificate stores; PC data is unaffected.
