# Release notes

## 1.0.0 — Hubuddy DeskHub, SIDYA Edition (2026-11-22)

First release of the Android adaptation of DeskHub for the Vivo 1902 (Android 9, API 28+).

**Android app (`com.sidya.deskhub`)**
* Native Kotlin app with no AndroidX, Material or OkHttp. Debug APK about 2.6 MB, optimised release about 140 KB.
* Dark charcoal or pure-black theme with walnut accents and teal highlights; portrait-first,
  two-column cards in landscape.
* Login number + PIN lock: PBKDF2 hashing, forced change of the temporary PIN, lockout with
  back-off, inactivity session timeout, logout, `FLAG_SECURE`.
* Tabs: Home, Monitor (+ Network, GitHub), AI (Claude Code), Controls (media, PC and phone
  shortcuts), Settings. Calendar and weather detail pages.
* PC companion over USB (`adb reverse`) or local Wi-Fi; pairing code → device token encrypted
  with the Android Keystore; connection test; configurable refresh and timeout; exponential
  reconnect back-off; polling stops in the background.
* Offline-first: local clock and date, last saved dashboard with its age, weather cache, clear
  "unavailable" states and never invented values.
* Weather from Open-Meteo (no key) and GitHub repository info, with an optional token.

**PC bridge (`companion-bridge/`)**
* Reuses the DeskHub companion's own data sources and command handling (no changes to DeskHub).
* Authenticated HTTP API on port 8765; localhost by default, private-LAN-only with `--lan`;
  validated commands; rate limits; device list and revoke.
* Optional mobile web dashboard served by the bridge, using the same pairing.

**Known limitations**
* Not yet tested on the physical Vivo 1902 or an emulator (see TEST_REPORT.md).
* HTTP on Wi-Fi is not encrypted (use USB mode or a trusted network).
* CPU temperature is not provided by DeskHub on Windows, so it shows as "unavailable". GPU
  temperature needs an NVIDIA GPU.
* Live updates use polling (default 5 s), not WebSocket push.
