# Test report — Hubuddy 3.3.0

Date: 2026-11-23. Environments: Linux build container (OpenJDK 21, Gradle 8.14, AGP 8.7, Kotlin 2.0, Android SDK Platform 34,
Robolectric 4.14, Python 3.13) and **GitHub Actions on Windows Server 2025** (Python 3.12) for the PC program.

> **Physical phone testing: PENDING.** No phone was attached and the container cannot run an emulator, so the Android
> behaviour was tested with Robolectric on **Android 5.0, 7.0, 8.0, 9, 11 and 14**, and the app's real network code was
> run against the real PC program (interop test). The camera, USB tethering, the Keystore on a device and firewalls on a real
> network were **not** exercised.

## Summary

| Suite | Result |
| --- | --- |
| PC program tests (Python) | ✅ **111 pass** on Linux (the 3.0.0 set of 101 also passed on **real Windows** in CI; 3.1.0's CI run follows the release) |
| `Hubuddy.exe --selftest` (20 checks: QR + PIN pairing, API, messages, files, discovery, forget) | ✅ passes as a Linux binary here, and as the **portable and the installed Windows program** in CI |
| Windows installer | ✅ built, **installed silently, self-tested, uninstalled cleanly** on a Windows runner |
| Android unit + Robolectric UI tests | ✅ **213 run, 0 failed** (13 skipped: 12 screenshot renders and the interop test unless a PC program runs) |
| Interop test (`tools/interop.sh`): the app's real client against the real PC program | ✅ QR pairing, replay refused, identity pinned, inbox both ways, files both ways, traversal refused, PIN pairing with matching compare code, wrong PIN refused, forget signs the phone out |
| Android Lint | ✅ 0 errors (one real finding fixed: a call missing before Android 7) |
| Builds | ✅ debug and release APK, `com.sidya.hubuddy` 3.0.0, minSdk 21, INTERNET + ACCESS_NETWORK_STATE + CAMERA (camera optional) |
| Agent packaged with PyInstaller | ✅ the packaged program serves its bundled web files and passes its self-test |

## What the pairing tests cover

* **Certificates:** a valid chain with the signed name, renewal that keeps the same authority and fingerprint, a replaced or missing
  identity detected rather than silently recreated, damaged files with recovery messages.
* **Invitations and PINs:** single use, expiry, replacement by a newer one, bad shapes, wrong PC id, lockout after repeated
  failures (and that a locked client cannot pair even with a good invitation), the host-wide budget, and the compare code
  producing the **same value on the phone and the PC** (shared test vectors, checked in both languages).
* **Pinned TLS (app):** accepts the right authority and name; rejects another authority, the right authority with the wrong PC name,
  and anything when the phone's own trust store is the only trust; a paired PC whose identity changed is refused.
* **Server:** plain HTTP on the TLS port gets nothing, unknown `Host` refused, wrong method/path/malformed bodies, 32-phone cap,
  keep-alive across many requests, garbage and oversize input, loopback page checks (DNS-rebinding names, foreign origins,
  missing local-action header), file name sanitising, size limits, no partial files, no symlinks, no traversal.
* **Discovery:** only the exact probe is answered, replies are rate limited and carry no secret; the app ignores malformed or
  non-local replies; address changes are followed by host id.
* **QR:** a real invitation-sized QR is decoded from a synthetic camera frame; blank frames decode to nothing.

## Added in 3.3.0 (trackpad, no sign-in, Windows window)

* **Gesture engine (17 tests incl. the sender):** one-finger move, tap versus long press versus slop, two-finger tap is a right click, slide scrolls without clicking or zooming,
  pinch out and in zoom in opposite directions, three fingers do nothing, a cancelled touch never clicks, no click or scroll while dragging, staggered finger events get a moment to catch up.
  A real tap and drag on the touchpad view reach the PC as a click, movement and a held button (Android UI test).
* **Ordered sender:** movement merges while a request is in flight, fractions are carried, events stay in order, a heartbeat flows only while a button is held and the final release is sent
  on close, 403 stops everything and is reported at once, repeated errors are reported rarely.
* **PC side:** press/hold buttons are tracked per phone and released by `release`, by 2 seconds of silence, when remote control is turned off and when a phone is forgotten; zoom is ignored while a button is held;
  letters and digits work in shortcuts; Linux `xdotool` argument lists for buttons, zoom and horizontal scroll are checked.
* **No sign-in:** the app starts straight at the dashboard; the optional lock refuses weak and mismatched PINs, locks again after `Lock now`, and unlocks only with the right PIN (4 tests).
* **Windows window:** its code runs against a stand-in for Tk (6 tests: QR and PIN, one countdown, copy invitation, paired phone appears, remote switch with confirmation, forget, close prompt, off-Windows firewall/autostart). It has **not** been drawn on a real screen.

## Added in 3.2.0 (Linux)

* **Debian 12 compatibility, verified here with the same versions Debian 12 ships:** Python 3.11 and `cryptography` 38 pass the core, server,
  remote and Linux suites (this caught and fixed three newer-library calls in the certificate code).
* **The .deb:** built, inspected, unpacked into a scratch root and run: `hubuddy --selftest` passes 20 of 20; idle it used about 38 MB of memory and about 0.15% of a CPU core
  (3 threads). CI repeats the install on a clean 64-bit Debian 12 container and runs the agent tests there (passed in CI). A 32-bit container run is best-effort and failed on CI networking, so 32-bit is **not verified**.
* **Linux command builders:** every `xdotool`, `systemctl`, `shutdown`, `xclip`, `wl-copy` and `xsel` call is a fixed argument list with no shell (text is data on stdin or after `--`);
  missing tools are reported as unavailable; a failing command is unavailable, not a crash; the window falls back to the terminal when Tk or a display is missing.
* **Not verified:** a real Linux desktop (X11/Wayland input, audio, systemd user sessions), real old hardware speed, Android 4.4 (not supported).

## Added in 3.1.0 (remote control and Wake)

* **Schema:** every input event is validated (types, ranges, whole numbers only, no booleans, text length and control characters, unknown
  keys, modifiers and event types, batch size); 16 bad shapes are refused.
* **Permission:** off by default for every phone; nothing reaches the backend while off; per phone; survives a restart; turning it off or
  forgetting all phones removes it; allowed, power, key and end-task actions are in the activity log.
* **Safety:** rate limit (429), protected programs refused, bad numbers are 400, an unsupported OS answers 501 instead of failing.
* **Wake-on-LAN:** the magic packet bytes are identical in the PC code and the app (102 bytes, MAC repeated 16 times).
* **App:** the Remote screen explains when it is off, shows the touchpad and sends typed text and keys when allowed (Android 5.0 to 14);
  the interop test also proves the 403 → allow → 501-on-Linux path against the real PC program.
* **Not verified:** mouse and keyboard injection through Windows `SendInput` on a user's own desktop (a mouse move was accepted on the CI Windows runner), audio volume, sleep/restart/shutdown,
  Wake-on-LAN hardware, and how the touchpad feels on a phone.

## Real bugs found by testing

* On **real Windows**, a refused request whose body was still unread made the connection reset before the reply arrived (Linux hid
  this). Fixed: small unused bodies are read first, large ones are discarded briefly after replying; covered by two tests.
* Lint: `HashMap.putIfAbsent` does not exist before Android 7 (the app supports Android 5).
* Tests: the JDK's TLS server and Robolectric's security provider clash during one-time initialisation, which made results depend on
  test order; the test server now hides that provider while it is created.

## Earlier suites (unchanged behaviour)

Authentication and PIN hashing, dashboard polling and offline cache, several PCs, UI flows on Android 5.0 to 14, dock mode,
Android 5.x without a Keystore, colour contrast in every look, and the collectors for system, Claude Code, calendar, GitHub,
network, shortcuts and media. See the 2.0.0 notes in the release history.

## Not run / pending

1. **A physical phone:** install, camera QR scanning on real cameras, Keystore on the device, USB tethering, how discovery behaves
   on your own router (guest Wi-Fi isolation and some mesh systems block it), battery behaviour, dock mode timing.
2. **Android emulator / instrumentation tests:** not possible without KVM here.
3. **The Windows window (tkinter):** it starts and its background program is tested, but a human has not looked at it on a screen.
   The firewall helper and the "start with Windows" setting are covered by command-building tests and the installer's rule, not by a
   user session. The installer's firewall rule was created in CI but not verified against a real phone.
4. **`build.ps1` and `start-agent.ps1`:** written for Windows PowerShell but never executed.
5. **Claude Code hooks against a real Claude Code, real NVIDIA GPU, key presses and media on a user desktop.**
6. **Release APK at runtime:** it builds and passes lint, but the tests ran against the debug build.
7. **SmartScreen:** the programs are unsigned.
