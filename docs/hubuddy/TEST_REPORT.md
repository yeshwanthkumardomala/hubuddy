# Test report — Hubuddy 3.0.0

Date: 2026-11-23. Environments: Linux build container (OpenJDK 21, Gradle 8.14, AGP 8.7, Kotlin 2.0, Android SDK Platform 34,
Robolectric 4.14, Python 3.13) and **GitHub Actions on Windows Server 2025** (Python 3.12) for the PC program.

> **Physical phone testing: PENDING.** No phone was attached and the container cannot run an emulator, so the Android
> behaviour was tested with Robolectric on **Android 5.0, 5.1, 7.0, 8.0, 9, 11 and 14**, and the app's real network code was
> run against the real PC program (interop test). The camera, USB tethering, the Keystore on a device and firewalls on a real
> network were **not** exercised.

## Summary

| Suite | Result |
| --- | --- |
| PC program tests (Python) | ✅ **101 pass** on Linux and on **real Windows** (CI) |
| `Hubuddy.exe --selftest` (20 checks: QR + PIN pairing, API, messages, files, discovery, forget) | ✅ passes as a Linux binary here, and as the **portable and the installed Windows program** in CI |
| Windows installer | ✅ built, **installed silently, self-tested, uninstalled cleanly** on a Windows runner |
| Android unit + Robolectric UI tests | ✅ **206 run, 0 failed** (13 skipped: 12 screenshot renders and the interop test unless a PC program runs) |
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
