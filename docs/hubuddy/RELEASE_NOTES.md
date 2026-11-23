# Release notes

## 3.4.0 — Logo, a window that never freezes, chord keyboard (2026-11-23)

* **Logo:** a new Hubuddy logo (an H whose crossbar is a signal pulse) on the phone app icon and header, the Windows program and installer icon, the
  Windows window, the Linux desktop entry, the PC's web page and the README.
* **The Windows window no longer freezes on opening.** A recording showed the old window going washed-out ("Not Responding") right after it opened,
  because it waited for the PC side to start without redrawing. Now the window opens at once with the logo and "Starting…", the PC side starts on its
  own thread, and the main window replaces the splash when ready. The window is built off-screen and shown once, the phone list is redrawn only when something
  changed, and a start-up error is explained in a message instead of a hang.
* **New-generation window:** dark or light following Windows, cards, a status pill, one row per phone with its own *Remote control* switch and Forget button,
  the QR code and PIN inside the window, sharp text on high-resolution screens, sensible size and position.
* **Keyboard chords on the phone:** hold Ctrl, Shift, Alt or Win with one finger and tap another key with a second finger (for example Ctrl + Shift + T, or Ctrl + Shift + F5);
  a quick tap on a modifier keeps it for exactly the next key. New F1 to F12 row and a full A to Z and 0 to 9 key set. A line shows the chord being held.
* **Not verified:** how the window looks on your screen is checked by a smoke test on a real Windows desktop in CI (it draws and saves a picture), but I have not been able to look at your screen; chords are tested
  with simulated touches, not a real two-finger press.

## 3.3.0 — Proper trackpad, no sign-in (2026-11-23)

* **No sign-in.** The app opens straight to the dashboard: no login number, no first-login PIN. An optional PIN lock (your own PIN) can be
  switched on and off in Settings → Security & app lock.
* **Remote control rebuilt around a real touchpad**, using the touchpad rules of the Tara project (MIT, see NOTICE):
  one finger moves the pointer (speed 0.5× to 3×, saved); a short tap is a left click; a two-finger tap is a right click; two fingers slide to
  scroll (vertical and horizontal) or pinch to zoom (Ctrl + wheel); three or more fingers do nothing; a cancelled touch never clicks.
  **Left, Right, Double and a Drag toggle** (press and hold, for dragging windows and selecting text); the PC lets a held button go by itself if the phone
  stops sending its heartbeat, is forgotten, or has remote control switched off. Movement is sent in order, one request at a time and merged
  when the network is slow, so the pointer stays smooth.
* **Keyboard:** text box, Enter/Backspace/Delete/Esc/Tab, arrows, Home/End/PgUp/PgDn, sticky Ctrl/Shift/Alt/Win, and a Shortcuts card (Copy, Paste, Cut, Undo,
  Select all, Save, Switch app, Close, Start, media keys). Works on Windows and on Linux (X11, `xdotool`).
* **Windows window rebuilt:** sharp text on high-resolution screens, resizable, pairing QR code and PIN inside the window (no browser needed),
  a Copy-invitation button, the per-phone *remote control* switch, a Quit/keep-running question when you close it (it used to just minimise), and no console window
  flashing when the firewall helper runs.
* **Linux:** everything in 3.2.0 (Debian package, headless, clipboard, remote control).
* **Not verified:** the touchpad's feel on a real phone, and the Windows window on a real screen (its code is tested against a stand-in for Tk, not drawn).

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
* **Not verified:** a real old PC, a real desktop session (X11 or Wayland input, audio, systemd user sessions). CI installs the package on a clean
  64-bit Debian 12 container (Debian's own Python 3.11 and cryptography 38) and runs the self-test and the whole test suite there. A 32-bit run is attempted but is best-effort (the CI network for 32-bit containers failed), so 32-bit and ARM are supported by design (pure Python) but **not verified**.

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
