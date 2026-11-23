# Release notes

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
