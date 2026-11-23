# Release notes

## 1.3.0 — Redesign, light theme and multiple PCs (2026-11-23)

* **New layout.** Landscape (and any wide screen) gets a navigation rail on the left and content in
  2 to 4 columns, so the whole height is for content. Portrait keeps a bottom bar, now with icons.
  Cards, headers and spacing were reworked; the status chip shows the connection and opens a PC picker.
* **No clock in everyday use.** The big clock and date left the Home screen. The time now appears only in
  desk mode, which starts after the idle time (default 5 minutes).
* **Desk mode defaults:** it now also starts on battery (setting "Also run when not charging", on by
  default) and stops itself at 15% battery so the phone is never drained flat. It follows the chosen theme
  by day and switches to pure black with a dim clock at night.
* **Appearance:** Auto (follows the phone), Dark, Black and **Light**. Colours were checked for readable
  contrast in every look; dialogs, status bar and system icons follow the theme.
* **Several PCs:** connect up to **8 PCs or laptops**. Each has its own name, address and pairing. The
  **All PCs** overview (Monitor tab) shows every machine's CPU, RAM, GPU or disk, temperature, network
  speed and Claude state at once; Home shows a one-line summary per PC; desk mode has an All PCs page. Tap
  a tile to make that PC the one Home, AI and Controls show. Each PC polls and reconnects on its own, so one
  offline PC never slows the others, and each keeps its own offline cache.
* A PC still called "My PC" or "PC 2" takes the computer name it reports.
* An earlier single-PC setup is converted automatically into the first entry.
* Tests: 174 run, all passing (UI tests on Android 5.0 to 14).

## 1.2.0 — Desk mode (2026-11-23)

* **Desk mode:** a DeskHub-style full-screen landscape display (both landscape directions) with a
  side rail of pages. It starts after the configured idle time (default 5 minutes) while charging,
  or from the new **Desk** button, and rotates through Clock, PC, Claude, Media, Calendar, Weather,
  Network and GitHub (default 12 s each, with a progress line). Pages without data are skipped.
* Drawn gauges (CPU, RAM, GPU, disk, Claude 5-hour and weekly limits), a CPU history chart, a
  latency chart, 7-day token bars and a GitHub contribution heatmap, all without extra libraries.
* A new Claude "waiting for permission or input" alert jumps straight to the Claude page.
* Night hours (default 23:00 to 07:00) or a sleeping PC screen: a dim clock only, lowest
  brightness. The layout shifts a few pixels each minute against screen burn-in.
* Read-only and safe: any touch leaves desk mode. It never extends an expired login session, so
  the PIN is asked again. The screen stays on only while charging unless you allow battery use.
* Settings → Desk mode: idle minutes, seconds per page, which pages, night hours, battery option, and "Open now".
* Tests: 147 run (UI tests on Android 5.0–14), all passing.

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
