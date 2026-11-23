# Release notes

## 1.1.0 — Works on any Android 5.0+ phone (2026-11-23)

* **minSdk lowered from 28 to 21**, so the app installs on Android 5.0 and newer (practically
  every Android phone in use), not only Android 9+.
* Portable PBKDF2-HMAC-SHA256 PIN hashing, checked against the RFC 7914 test vector and the
  JDK implementation. The hash format is now `pbkdf2-sha256$…` (hex).
* Android 5.x fallback: secrets in memory only when the Keystore has no AES support; a legacy
  network-type API before Android 6; a non-adaptive launcher icon for Android 5–7.
* UI tests now run on Android 5.0, 7.0, 8.0, 9, 11 and 14, with an extra Android 5.0/5.1
  check of the Keystore-less path: 106 tests, all passing.
* Smaller APKs: debug 956,974 bytes, release 79,355 bytes.
* Known limit on Android 5.0–7.0: these phones may not trust the certificates of the weather
  and GitHub services. Those cards then show "unavailable", while everything from the PC works
  (it uses plain local HTTP).

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
