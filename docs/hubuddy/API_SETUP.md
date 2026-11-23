# Agent setup and API

The phone reads data from the **Hubuddy agent** on each PC or laptop.

## 1. Install the agent on a PC

1. Install **Python 3.11 or newer** (python.org, tick "Add to PATH"). For USB mode also install the Android
   platform-tools (`adb`).
2. Easiest: download `Hubuddy-Agent-2.0.0.zip` from `releases/`, unzip it and double-click `start-agent.bat`.
   Or get this repository (`git clone`), then in PowerShell:

```powershell
cd hubuddy\agent
.\start-agent.ps1            # USB mode: runs adb reverse for you, listens on 127.0.0.1 only
.\start-agent.ps1 -Lan       # Wi-Fi mode: reachable from your local network (private addresses only)
.\start-agent.ps1 -Pair      # print a new pairing code
```

The first run creates a private Python environment (`.venv`) and installs `requirements.txt`. The agent prints
a pairing code:

```
  Pairing code: 482913   (valid 10 min, single use)
```

Manual equivalent: `pip install -r requirements.txt` then `python -m hubuddy_agent [--lan] [--pair] [--port 8765]`.
Manage phones with `--list-devices` and `--revoke <id|all>`. Pairings are stored as SHA-256 hashes in
`%USERPROFILE%\.hubuddy\agent_devices.json`.

## 2. Optional: configuration

Copy `agent\config.example.toml` to `agent\config.toml` (or `%USERPROFILE%\.hubuddy\config.toml`):

| Section | What it does |
| --- | --- |
| `[[calendar.feeds]]` | ICS calendar links or files (Google, iCloud, Outlook and others all offer an ICS link) |
| `[[shortcuts]]` | Buttons on the phone: `lock`, `open` (link or file), `run` (program), `keys` (e.g. `win+shift+s`), `volume`. The PC runs exactly what is written here; programs start without a shell |
| `[internet]` | Probe targets for latency and loss; `enabled = false` turns it off |
| `[github]` | `token_env` names an environment variable holding a GitHub token for contribution data (never put the token in the file) |

## 3. Optional: Claude Code limits and alerts

Token usage appears without any setup. For plan limits and "waiting for permission" alerts:

```powershell
cd hubuddy\agent
python -m hubuddy_agent.claude_setup show     # preview, changes nothing
python -m hubuddy_agent.claude_setup apply    # adds a hook and a status line (backup written)
python -m hubuddy_agent.claude_setup remove   # removes only Hubuddy's entries
```

Your existing hooks are kept, and an existing status line is chained (it keeps working). Restart Claude Code
sessions afterwards.

## 4. Connect the phone

**USB:** run the agent (USB mode), connect the phone with USB debugging on, make sure `adb reverse tcp:8765 tcp:8765`
has run (the script does it; run it again after replugging). In the app: Settings → PCs and laptops → USB →
*Test connection* → enter the pairing code → *Pair*.

**Wi-Fi:** start the agent with `-Lan` and allow Python through Windows Firewall for **Private** networks only.
Find the PC's IPv4 address with `ipconfig` (for example `192.168.1.100`, only an example). In the app choose
*Local Wi-Fi*, enter `192.168.1.100:8765`, *Save*, *Test connection*, then pair. The app accepts only loopback,
private (10/8, 172.16/12, 192.168/16), link-local and `*.local` addresses. Never forward port 8765 on your router.

**Several PCs (up to 8):** run an agent on each, then in the app tap *+ Add PC*, give it a name, enter its address
and pair it. USB serves one cable, so use Wi-Fi for the others. See them together on Monitor → All PCs.

## 5. API reference

Base URL `http://127.0.0.1:8765` (USB) or `http://<PC IP>:8765`. Everything except `ping` and `pair` needs
`Authorization: Bearer <device token>`. Responses are JSON, `Cache-Control: no-store`.

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/api/ping` | — | `{"ok":true,"service":"hubuddy-agent","version":"2.0.0"}` |
| POST | `/api/pair` | `{"code":"482913","device":"phone"}` | `{"token":"…"}` · 401 wrong code · 403 closed · 429 too many |
| GET | `/api/snapshot` | — | `{server_time, pc, stats, claude, alert, sessions_info, media, calendar, network, shortcuts, github}`; a section is `null` when unavailable |
| GET | `/api/status` `/api/stats` `/api/claude` `/api/alerts` `/api/calendar` `/api/media` `/api/network` `/api/shortcuts` `/api/github` | — | `{available, data}` |
| POST | `/api/media` | `{"cmd":"toggle"|"next"|"prev"}` | `{"ok":true}` |
| POST | `/api/shortcuts/run` | `{"id":0}` | `{"ok":true}` · 400 unknown id |
| POST | `/api/alerts/action` | `{"id":"…","action":"dismiss"}` | `{"ok":true}` |
| POST | `/api/speedtest` | `{}` | `{"ok":true}` (at most one per 2 minutes) |

Errors are `{"error":"…"}` with 400, 401, 403, 404, 405, 408, 413 or 429. Limits: 16 KB headers, 16 KB body,
10 s to send a request, 10 failed auth attempts per minute per client.

## 6. Troubleshooting

| In the app | Fix |
| --- | --- |
| "Agent unreachable" (USB) | Agent running? Run `adb reverse tcp:8765 tcp:8765` again after replugging |
| "Agent unreachable" (Wi-Fi) | Started with `-Lan`? Firewall allows Python on Private networks? Same Wi-Fi? |
| "Only local addresses are allowed" | Use the PC's LAN IP, not a public address |
| "Agent rejected the pairing token" | Pairing was revoked: start the agent with `-Pair` and pair again |
| "pairing is closed" | Code expired, used, or 5 wrong tries: restart the agent with `-Pair` |
| AI page: "Claude Code agent not connected" | No Claude Code data on that PC yet, or the agent is not paired |
| Limits and alerts missing | Run `python -m hubuddy_agent.claude_setup apply` |
| What's playing is missing | Needs the `winrt` packages (installed by `requirements.txt` on Windows) and something playing |
