# Release notes

## 3.2.0 — Linux (2026-11-23)

* **Debian package** `hubuddy_3.2.0_all.deb` (about 100 KB): one package for **any CPU** (32-bit and 64-bit x86, ARM, ...) on **Debian 12 and
  newer** and Ubuntu 22.04+, using the system's own Python 3.11. Works on minimal ("lite") installs: **no desktop needed**. Pair with
  `hubuddy --show-invite` (QR in the terminal) or `hubuddy --pin`, or open `http://127.0.0.1:8766/` in a browser on that PC.
  Start at login: `systemctl --user enable --now hubuddy`. Firewall help: `hubuddy --firewall-help` (ufw and firewalld commands for your private network).
* **Light:** about 38 MB of memory and about 0.15% of one CPU core when idle, 3 threads (measured on a 64-bit machine with Python 3.11).
* **On Linux the phone can:** read CPU, memory, disk, network, Claude Code and calendar data, send text/links/files both ways, set the PC
  clipboard (`wl-copy`, `xclip` or `xsel`), and, if you allow it per phone, use the touchpad and keyboard (`xdotool`, X11), volume
  (via the volume keys), and sleep/restart/shut down (`systemctl`, `shutdown`; a one-minute countdown that Cancel stops). Anything the PC lacks is reported as unavailable.
* **Compatibility fix:** certificates now work with the older `cryptography` that Debian 12 ships (version 38).
* The optional Hubuddy window needs `python3-tk` and a desktop; without them the same program runs without a window.
* **Not available on Linux yet:** now-playing and media buttons, GitHub/Windows-only extras, a Wayland-native input backend (X11 is supported; on Wayland remote input needs XWayland or is reported unavailable),
  `.rpm`, Flatpak and Snap packages.
* **Phones:** Android 5.0+ as before. **Android 4.4 and older are not supported** (no usable TLS 1.2 and no safe key storage there); iPhone is not supported.
* **Not verified:** a real old PC, a real desktop session (X11 or Wayland input, audio, systemd user sessions). CI installs the package on clean
  64-bit and 32-bit Debian 12 containers, runs the self-test and the tests there; the 32-bit result is in the release's CI run.

## 3.1.0 — Remote control and Wake (2026-11-23)

* **Remote control from the phone:** a touchpad (drag, tap to click, two fingers to scroll or right-click), a keyboard box with Enter,
  Backspace, Esc, Tab and arrows, volume (quieter, mute, louder), and power (sleep, restart, shut down with a 15-second countdown and
  a cancel button). Restart and shut down ask for confirmation on the phone.
* **Off by default, and under the PC owner's control:** each paired phone has its own *Remote control* switch in Hubuddy on the PC (with a
  confirmation when turning it on). Until it is on, every remote action is refused. Every keyboard, power and end-task action is
  written to the activity log; actions are validated against a strict schema (limits on movement, text and keys), rate limited, and
  never use a shell. Forgetting a phone removes the permission.
* **Task manager lite (PC side):** the program can list the biggest programs and end one on request, refusing system programs
  and itself. The phone screen for it is not built yet.
* **Wake-on-LAN:** an offline paired PC shows *Wake this PC* on the All PCs screen; the phone sends the magic packet on its own network.
  Needs Wake-on-LAN enabled in the PC's BIOS and network adapter, the same network, and does not work over USB tethering.
* **Not in this release:** brightness, app/window launcher and decks, laptop battery on the tiles, screenshots, timers and world
  clocks, the rebuilt Settings page, Hindi and Telugu (planned for 3.2 and 3.3).
* **Limits:** Windows blocks injected input into elevated windows and the lock screen; the Windows input code has been run only through CI's
  self-test and fakes, **not on a real desktop**, and the touchpad has not been felt on a real phone.

## 3.0.0 — Pair like a pro (2026-11-23)

**Connecting, redesigned.** No Developer options, no `adb`, no typing IP addresses.

* **Three ways in:** scan a QR code on the PC; or find the PC on the network and type a PIN after checking a compare
  code; or, on the same Wi-Fi or LAN (also USB tethering), let the phone find known PCs by itself. A pasted invitation and a
  typed address remain as fallbacks.
* **Encrypted and pinned:** every PC makes its own private certificate authority; the phone trusts only that authority and the
  PC's signed name. QR secrets and PINs last two minutes and work once; five wrong tries lock a phone out for a minute and a
  PC-wide budget protects against guessing. The PC's own page is loopback-only with DNS-rebinding defences.
* **Many phones, many PCs:** a PC accepts up to 32 phones (each forgettable alone); a phone keeps up to 8 PCs and finds them again
  by their permanent id when addresses change.
* **Two-way:** send text, links and the clipboard to the PC; the PC sends text and links to the phone; files both ways into
  `Documents\Hubuddy`; the PC shows each phone's battery and network; an activity log and a notice when a new phone pairs.
* **Windows program:** `Hubuddy.exe` (portable) and `HubuddySetup.exe` (installer with a private-network firewall rule and an
  autostart option), built and self-tested on real Windows by CI. `Hubuddy.exe --selftest` checks pairing and the API against itself.
* **App:** QR scanner using the framework camera and zxing core only (APK stays small), new Send & files screen, a clearer
  Add-a-PC card, HTTPS-only network policy. The USB/adb setup is gone.

**Breaking change:** phones and PCs must both be updated to 3.x; old 2.x pairings are not carried over.

**Credit:** the pairing design follows the approach of the Tara project (MIT, Copyright (c) 2026 Ali Saad); see `NOTICE`.

**Known limits**
* Not tested on a physical phone: camera QR scanning on real cameras, USB tethering, Keystore on a device, firewalls on your network.
* The Windows programs are unsigned (SmartScreen shows "unknown publisher"). The APK is signed with a debug key, so remove an
  older Hubuddy build before installing one signed by another source.
* Wake-on-LAN, touchpad and keyboard, volume/brightness/power, screenshots, timers and world clocks, a rebuilt Settings main
  page and Hindi/Telugu are planned for 3.1 to 3.3, not in this release.
* Discovery cannot cross routers or guest Wi-Fi isolation; use a QR code or USB tethering there.

## 2.0.0 — Hubuddy (2026-11-23)

The first public release of Hubuddy as its own product.

**App** (`com.sidya.hubuddy`, Android 5.0+)
* Dashboard for up to **8 PCs and laptops**, with an **All PCs** overview that shows every machine's CPU, RAM,
  GPU or disk, temperature, network speed and Claude state on one screen.
* Pages: Home, Monitor, AI (Claude Code), Controls, Settings, plus Calendar, Weather, Network and GitHub.
* **Dock mode:** after 5 idle minutes (also on battery, stopping at 15%) a full-screen landscape display
  rotates through the clock and every page with data, jumps to Claude alerts, dims at night, and guards against burn-in.
* **Looks:** Auto, Dark, Black and Light themes; side rail and 2 to 4 columns in landscape, bottom bar in portrait.
* Login number + PIN lock with a forced PIN change, lockout, session timeout and Keystore-encrypted secrets.
  The temporary first-login credentials are baked into your own build, not stored in the repository.
* Offline-first: the last readings of every PC are kept and shown with their age; each PC reconnects on its own.

**Agent** (`agent/`, Python 3.11+)
* Collects readings with its own collectors: system and NVIDIA GPU, Claude Code usage and alerts, media,
  calendar (ICS), internet health, GitHub contributions and shortcut buttons.
* Authenticated local API: one-time pairing code, per-phone tokens stored as hashes, private-network clients
  only, validated commands, rate limits. Shortcut programs start without a shell.
* `claude_setup` adds Claude Code's hook and status line safely (backup, keeps your hooks, chains your status line, removable).
* Optional mobile web dashboard served by the agent.

**Known limits**
* Not yet tested on a physical phone or an emulator, and the PowerShell scripts and Windows-only agent parts
  (media, key presses, locking, a real NVIDIA GPU) have not been run on Windows. See the test report.
* HTTP on Wi-Fi is not encrypted; use USB or a trusted network.
* No "bring the Claude window to the front" action, no CPU temperature on Windows, polling every 5 s instead of push.
