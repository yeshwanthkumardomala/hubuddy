# Architecture

Hubuddy turns an Android phone into a dashboard and ambient display for your Windows PCs and laptops.

```
 PC 1 (Windows)      PC 2 (laptop)     ...up to 8              Phone(s) (Android 5.0+)
 ┌───────────────┐   ┌───────────────┐   same Wi-Fi / LAN     ┌─────────────────────────────┐
 │ Hubuddy       │   │ Hubuddy       │◄─ or USB tethering ───►│ Hubuddy app                 │
 │  HTTPS :8765  │   │  HTTPS :8765  │   (private only)       │   Home · Monitor · AI ·     │
 │  UDP 41236    │   │  UDP 41236    │                        │   Controls · Settings       │
 │  page :8766   │   │  page :8766   │                        │   All PCs · Dock · Send     │
 └───────────────┘   └───────────────┘                        └─────────────────────────────┘
```

* **PC program** (`agent/`, Python 3.11+, shipped as `Hubuddy.exe`): runs on each computer, collects readings with its
  own collectors, and serves them over a small authenticated HTTPS API. A window (tkinter) starts pairing, lists phones
  and sets up the firewall and autostart.
* **App** (`android/hubuddy/`, Kotlin): talks to one or more PCs, shows their data, and works from its last
  saved readings when a PC is off.

## Agent collectors

| Collector | Source | Notes |
| --- | --- | --- |
| System | `psutil`; `nvidia-smi` for NVIDIA GPUs | CPU, RAM, disk, network throughput, GPU load/temperature/VRAM. GPU asked every 5 s, and once a minute when absent. Anything unavailable is left out, never invented |
| Claude Code | Claude Code's own session logs (`~/.claude/projects`) and two small scripts (`claude_hook.py`, `claude_statusline.py`) | Token usage, 5-hour blocks, plan limits, and "waiting for permission or input" alerts. `claude_setup` adds the scripts to `~/.claude/settings.json` with a backup, keeps your existing hooks and chains your existing status line, and can remove only its own entries |
| Media | Windows media sessions (`winrt`) | Title, artist, progress, play/pause/next/previous |
| Calendar | ICS links or files | Repeating events, all-day events, several feeds |
| Network | TCP-connect probes, byte counters, a simple speed test | Latency, jitter, loss, history, data used today and this month (persisted) |
| GitHub | GraphQL with a token from an environment variable | Contribution heatmap data, streaks |
| Shortcuts | `config.toml` | Lock, open a link or file, run a program, key combination, volume. Started **without a shell** |

## App

* **No AndroidX, Material or OkHttp.** Framework widgets, `HttpURLConnection` and `org.json`. Debug APK about
  1.4 MB, release about 170 KB. No native code. Works on Android 5.0+ (minSdk 21): PBKDF2-SHA256 is built on
  HmacSHA256, Base64 comes from `android.util`, and the network type uses the older API before Android 6. On
  Android 5.x, which has no AES keys in the Keystore, secrets stay in memory only and are never written in clear text.
* **Screens:** Home, Monitor (with **All PCs**), AI, Controls, Settings, plus Calendar, Weather, Network and
  GitHub pages. Portrait has a bottom bar; landscape has a side rail and 2 to 4 card columns.
* **Dock mode:** a full-screen landscape display for a phone on a stand. After the idle time (default 5 min,
  also on battery, stopping at 15%) it rotates through the clock and every page that has data, jumps to Claude
  alerts, and dims to a clock at night. Touch leaves it and never extends an expired login.
* **Several PCs:** `PcRegistry` keeps up to 8 profiles with a pairing token each (Keystore-encrypted).
  `DashboardRepository` runs one independent poller per PC (own back-off and offline cache). Polling stops when the app is not visible.
* **Themes:** Auto, Dark, Black and Light, with a test for readable contrast in every look.
* **Never fakes data:** a value an agent did not send shows "unavailable". Offline, the last saved readings are shown with their age.

## Pairing and transport (how a phone comes to trust a PC)

1. **Identity.** On first start each PC creates a private certificate authority (ECDSA P-256, 10 years) and a server
   certificate (90 days, renewed with the same authority) carrying the signed name `host-<id>.hubuddy.invalid`. A marker file
   detects a replaced identity and refuses to silently create a new one.
2. **QR invitation.** The QR holds the authority, addresses, port and a random one-time secret that dies after two minutes
   (the PC keeps only its SHA-256). Scanning it pins that authority on the phone: no other authority, including the phone's
   own list, is ever consulted for this PC, and the certificate must carry the PC's signed name.
3. **PIN pairing.** A fresh 6-digit PIN (two minutes, single use) and a 12-character *compare code*, computed from the PC's
   identity. The phone shows the code it received; the user checks they match before the PIN is sent, so a man-in-the-middle
   on the network is caught. QR is stronger; PIN needs that visual check.
4. **Token.** Pairing returns a 32-byte random token, stored as a SHA-256 on the PC and Keystore-encrypted on the phone.
   Tokens last 180 days, there are at most 32 per PC, and each phone can be forgotten alone.
5. **Defences.** Five wrong claims lock a client for a minute; a host-wide budget (30 failures per 10 minutes) pauses
   pairing for everyone; the `Host` header must be a known name (DNS-rebinding); only private-network peers are served; the
   PC's own page needs loopback, a loopback `Host` and a local-action header.
6. **Finding a PC.** The phone broadcasts a UDP probe on every IPv4 link that is up, including USB tethering
   (`rndis`/`usb`/`ncm`), falls back to probing the subnet, and re-finds a paired PC by host id when its address changes.

## Security summary

| Concern | Measure |
| --- | --- |
| App lock | Optional and off by default (no sign-in); when switched on in Settings: your own PIN (PBKDF2-HMAC-SHA256, 60,000 iterations, constant-time compare); lockout; session timeout |
| Secrets on the phone | AES-256-GCM with a non-exportable Android Keystore key; memory-only where unavailable (Android 5.x); `allowBackup=false` |
| Transport | HTTPS only, pinned per-PC authority, no cleartext anywhere in the app |
| Exposure | Private-network clients only; the app refuses to send anything to a public address; never forward the port on a router |
| Commands | Fixed verbs with validated arguments; shortcut programs start without a shell; files are confined to two folders with sanitised names, size limits and no symlinks |
| Local page | Loopback only, with `Host`, origin and local-action checks and a strict Content-Security-Policy |
| Limits not covered | An attacker already on your PC, or a PC whose identity folder is stolen, can impersonate that PC; the program is unsigned (Windows SmartScreen) |

## Repository layout

```
android/hubuddy/   Android app and build.ps1
agent/             PC agent, start-agent.ps1, config.example.toml, tests, optional web dashboard
docs/hubuddy/      this documentation and screenshots
installer/         Inno Setup recipe for HubuddySetup.exe
.github/workflows/ CI: tests, Windows build, installer, release
releases/          downloads: APK, Hubuddy.exe, HubuddySetup.exe, checksums
tools/             interop.sh, make_test_pki.py, package_agent.py
```
