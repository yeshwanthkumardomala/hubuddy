# Companion setup and API — Hubuddy DeskHub

The phone reads data from the **Hubuddy bridge** (`companion-bridge/`). The bridge runs on your
Windows PC inside the DeskHub companion's Python environment and reuses its data sources.

## 1. Prerequisites on the PC

1. A working DeskHub companion checkout: <https://github.com/zacharyinserra/deskhub>, folder
   `companion/`. Follow DeskHub's README to install `uv` and run `uv run deskhub-setup`, which
   creates `config.toml`. You do **not** need the ESP32 board.
2. Optional, for Claude Code limits and waiting alerts: in that folder, run DeskHub's
   `uv run python install_statusline.py` and `uv run python install_claude.py`. Token counts work
   without these, because DeskHub reads Claude Code's local session logs.
3. `adb` (Android platform-tools) on PATH for USB mode.

## 2. Start the bridge

```powershell
cd hubuddy\companion-bridge
# USB mode (default): listens on 127.0.0.1:8765 and runs adb reverse for you
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion
# Wi-Fi mode: listen on the LAN (private client addresses only)
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion -Lan
# Pair another device later
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion -Pair
# Keep the ESP32 board working from the same process
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion -AlsoBoard
```

Equivalent manual command:

```powershell
cd C:\path\to\deskhub\companion
$env:PYTHONPATH = "C:\path\to\hubuddy\companion-bridge"
uv run python -m hubuddy_bridge --config config.toml [--lan] [--pair] [--port 8765]
```

On the first start, or with `--pair`, the console prints a pairing code:

```
  Pairing code: 482913   (valid 10 min, single use)
```

Device management: `--list-devices`, `--revoke <id>` and `--revoke all`. Paired devices are
stored as SHA-256 hashes in `%USERPROFILE%\.hubuddy\bridge_devices.json`.

The original DeskHub companion can keep running. The bridge does not connect to the board
unless you pass `--also-board`. In that case stop the original companion first, because two
board clients would keep knocking each other off.

## 3. Connect the phone

### Mode A — USB (`adb reverse`)

Startup sequence:

1. Start the bridge (USB mode).
2. Connect the phone, then run `adb reverse tcp:8765 tcp:8765`. `start-bridge.ps1` does this
   when `adb` is on PATH. Re-run it after reconnecting the cable.
3. In the app: Settings → Connection → **USB**, port `8765` → *Test connection*.
4. Enter the pairing code → **Pair**.

The phone talks to `http://127.0.0.1:8765`, and adb carries it over the cable.

### Mode B — Local Wi-Fi

1. Start the bridge with `-Lan`. Allow Python through Windows Firewall for **Private**
   networks only.
2. Find the PC's IPv4 address with `ipconfig`, for example `192.168.1.100` (that is only an
   example).
3. In the app: Settings → Connection → **Local Wi-Fi**, address `192.168.1.100:8765` →
   *Save* → *Test connection* → pair.

The app accepts only loopback, private (10/8, 172.16/12, 192.168/16), link-local and `*.local`
addresses. Plain HTTP on Wi-Fi is **not encrypted**, so use a network you trust. Never forward
port 8765 on your router.

## 3b. Connecting several PCs and laptops (up to 8)

Each computer runs its own bridge (section 2) and is paired once:

1. On every computer: set up the DeskHub companion and start the bridge with `-Lan` (Wi-Fi). Only one
   computer can use USB (`adb reverse` maps the phone to the one cable); use Wi-Fi for the rest.
2. In the app: **Settings → PCs and laptops → + Add PC**. Give it a name, choose *Local Wi-Fi*, enter that
   computer's IP and port (for example `192.168.1.101:8765`), *Save*, *Test connection*, then enter the
   pairing code its bridge printed and tap *Pair*. Repeat for each computer.
3. See everything together on **Monitor → All PCs**, or pick one computer with the chips at the top (that
   computer then also drives Home, AI and Controls). Home shows a one-line summary per PC, and desk mode
   has an **All PCs** page.

A PC named "My PC" or "PC 2" takes the computer name it reports once it connects. Offline PCs show their
last readings dimmed with "last seen". Remove a PC with *Remove PC* in its settings.

## 4. Optional integrations

| Feature | Where to configure |
| --- | --- |
| Calendar | DeskHub `config.toml` → `[calendar] feeds` (ICS, Google, iCloud, Outlook) or `uv run deskhub-calendars` |
| PC shortcuts | DeskHub `config.toml` → `[[tiles]]` (keys, apps, folders, URLs, volume, lock…). The app shows them and asks before running one. |
| GitHub contributions | `uv run deskhub-setup github` (token kept in Windows Credential Manager on the PC) |
| GitHub repository card | Phone: Settings → GitHub (`owner/repo`; token optional, encrypted on the phone) |
| Weather | Phone: Settings → Weather location (Open-Meteo, no key) |
| Internet stats / speed test | DeskHub `config.toml` → `[internet]` |

## 5. API reference

Base URL: `http://127.0.0.1:8765` (USB) or `http://<PC LAN IP>:8765`.
Everything except `ping` and `pair` requires `Authorization: Bearer <device token>`.
Responses are JSON with `Cache-Control: no-store`.

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/api/ping` | — | `{"ok":true,"service":"hubuddy-bridge","version":"1.0.0"}` |
| POST | `/api/pair` | `{"code":"482913","device":"vivo 1902"}` | `{"token":"…"}` · 401 wrong code · 403 pairing closed · 429 too many attempts |
| GET | `/api/snapshot` | — | `{server_time, pc, stats, claude, alert, sessions_info, media, calendar, network, shortcuts, github, printer, steam}`; a section is `null` when unavailable |
| GET | `/api/status` | — | `{available, data: {online, asleep, hostname, bridge_uptime_s}}` |
| GET | `/api/stats` | — | `{available, data: {cpu, ram_used, ram_total, disk, net_down, net_up, gpu?, gpu_temp?, vram_used?, vram_total?, cpu_name, gpu_name}}` (GB, %, KB/s) |
| GET | `/api/claude` | — | `{available, data: {limits:{five_hour,seven_day:{pct,reset}}, today, block, last_hour, days[7], models, last_active, provider}}` |
| GET | `/api/alerts` | — | `{available, data: {id, kind:"permission"|"input", title, detail, project}}` |
| GET | `/api/calendar` | — | `{available, data: {events:[{title, loc, cal, start, end, all_day, color}], days}}` |
| GET | `/api/media` | — | `{available, data: {active, playing, title, artist, album, source, pos, dur}}` |
| GET | `/api/network` | — | `{available, data: {online, latency_ms, jitter_ms, loss_pct, today_bytes, month_bytes, speed}}` |
| GET | `/api/shortcuts` | — | `{available, data: {tiles:[{id,label,icon,color}], on:[…]}}` |
| GET | `/api/github` | — | `{available, data: {login, total, today, week, streak, …}}` |
| GET | `/api/weather` | — | `{"available":false,"reason":"…fetched by the phone…"}` |
| POST | `/api/media` | `{"cmd":"toggle"|"next"|"prev"}` | `{"ok":true}` |
| POST | `/api/shortcuts/run` | `{"id":0}` | `{"ok":true}` · 400 unknown id |
| POST | `/api/alerts/action` | `{"id":"<alert id>","action":"focus"|"dismiss"}` | `{"ok":true}` |
| POST | `/api/speedtest` | `{}` | `{"ok":true}` (DeskHub limits it to one per 2 min) |

Errors: `{"error":"…"}` with 400, 401, 403, 404, 405, 408, 413 or 429. Limits: 16 KB headers,
16 KB body, 10 s to send a request, and 10 failed auth attempts per minute per client.

## 6. Troubleshooting

| Symptom in app | Fix |
| --- | --- |
| "Companion unreachable…" (USB) | Bridge running? Run `adb reverse tcp:8765 tcp:8765` again after replugging. |
| "Companion unreachable…" (Wi-Fi) | Started with `-Lan`? Firewall allows Python on Private networks? Phone on the same Wi-Fi? |
| "Only local addresses are allowed" | Use the PC's LAN IP, not a public IP or domain name. |
| "Companion rejected the pairing token" | Device revoked or `bridge_devices.json` deleted: start with `-Pair` and pair again. |
| "pairing is closed" | The code expired, was used, or had 5 wrong tries: restart the bridge with `-Pair`. |
| AI page: "Claude Code companion not connected." | Bridge not paired or reachable, or DeskHub found no Claude Code data on the PC. |
| Limits missing | Install DeskHub's status line bridge (`install_statusline.py`). |
