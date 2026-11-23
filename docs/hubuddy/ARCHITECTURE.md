# Architecture

Hubuddy turns an Android phone into a dashboard and ambient display for your Windows PCs and laptops.

```
 PC 1 (Windows)      PC 2 (laptop)     ...up to 8              Phone (Android 5.0+)
 ┌───────────────┐   ┌───────────────┐                        ┌─────────────────────────────┐
 │ Hubuddy agent │   │ Hubuddy agent │   USB (one PC)         │ Hubuddy app                 │
 │   :8765       │◄──┤   :8765       │◄─ or Wi-Fi (any) ─────►│   Home · Monitor · AI ·     │
 │ collectors    │   │ collectors    │   LAN only             │   Controls · Settings       │
 └───────────────┘   └───────────────┘                        │   All PCs · Dock mode       │
                                                              └─────────────────────────────┘
```

* **Agent** (`agent/`, Python 3.11+): runs on each computer, collects readings with its own collectors, and
  serves them over a small authenticated HTTP API. It is the only thing on the PC.
* **App** (`android/hubuddy/`, Kotlin): talks to one or more agents, shows their data, and works from its last
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
  1 MB, release about 120 KB. No native code. Works on Android 5.0+ (minSdk 21): PBKDF2-SHA256 is built on
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

## Security

| Concern | Measure |
| --- | --- |
| App lock | Login number + PIN. PIN stored only as PBKDF2-HMAC-SHA256 (60,000 iterations, random salt), compared in constant time |
| First login | Temporary credentials are baked into your own build (not stored in the repository); the app forces a new PIN on first login and dock/dashboard stay locked until then |
| Brute force | 5 failures lock for 30 s, doubling up to 15 min, persisted across restarts |
| Session | Random id in the encrypted store, inactivity timeout (default 15 min) |
| Secrets at rest | AES-256-GCM with a non-exportable Android Keystore key; memory-only where the Keystore cannot do it; `allowBackup=false` |
| Pairing | Single-use, expiring 6-digit code (dies after 5 wrong guesses) gives each phone its own 256-bit token; the PC stores only its SHA-256 |
| Exposure | Agent binds `127.0.0.1` by default; `--lan` accepts private-network clients only; the app refuses to send its token to a public address; failed auth is rate-limited |
| Commands | Fixed verbs with validated arguments. The phone never sends a command line |
| Transport | Plain HTTP is **not encrypted**. USB keeps traffic on the cable; on Wi-Fi use a network you trust |

## Repository layout

```
android/hubuddy/   Android app and build.ps1
agent/             PC agent, start-agent.ps1, config.example.toml, tests, optional web dashboard
docs/hubuddy/      this documentation and screenshots
releases/          installable APK and checksum
```
