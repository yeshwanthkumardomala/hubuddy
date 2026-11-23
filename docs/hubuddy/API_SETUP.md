# PC setup and API

The phone reads data from **Hubuddy on each PC or laptop**. You do not need Developer options, a cable
or any typing of IP addresses.

## 1. Install Hubuddy on a PC

**Easiest (Windows 10/11):** download `HubuddySetup-<version>.exe` (installer) or `Hubuddy-<version>.exe` (portable, no
install) from the [Releases page](https://github.com/yeshwanthkumardomala/hubuddy/releases) or the `releases/` folder, and
open it. Windows may show "unknown publisher" because the program is not code-signed: choose *More info → Run anyway*.
The installer can add a Windows Firewall rule for **private** networks only and start Hubuddy when you sign in.

**From source (any OS with Python 3.11+):**

```powershell
cd hubuddy\agent
.\start-agent.ps1 -Window        # the Hubuddy window (Windows)
.\start-agent.ps1 -Invite        # or: print a pairing QR code in this window
```

Manual equivalent: `pip install -r requirements.txt`, then `python -m hubuddy_agent [--show-invite] [--pin]`.
Identity, paired phones and logs live in `%USERPROFILE%\.hubuddy`. Manage phones with `--list-phones` and `--forget <id|all>`,
or use the window / the page at `http://127.0.0.1:8766/`.

## 2. Connect the phone (three ways, all encrypted)

Phone and PC must be on the same network: the same Wi-Fi, the same router by cable, or **USB tethering** (phone
Settings → Hotspot & tethering → *USB tethering*, an ordinary switch, no developer options).

| Way | Steps |
| --- | --- |
| **QR code** (strongest) | PC: Hubuddy → *Pair a phone*. Phone: Settings → Add a PC or laptop → *Scan QR code*. The QR carries the PC's identity and a one-time 2-minute secret |
| **PIN** (no camera) | PC: Hubuddy → *Pair with a PIN* (shows a PIN and a compare code). Phone: *Find PCs on this network* → tap the PC → check that the compare code matches the PC's → enter the PIN |
| **Paste or type** | *Paste invitation* (copy the text under the QR) or *Enter address* if discovery is blocked |

After pairing, the phone finds the PC again by itself if its address changes (it keeps the PC's permanent id).
**Many phones, many PCs:** a PC accepts up to 32 phones and a phone keeps up to 8 PCs; each pair has its own key.
Forget a phone on the PC (window or page) or unpair a PC on the phone at any time.

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

## 4. API reference

Base URL `https://<PC address>:<port>` (default port 8765; another free port is used if it is busy and is advertised by
discovery). The certificate is signed by the PC's own authority (pinned by the phone), with the name
`host-<hostId>.hubuddy.invalid`. Everything except `/health` and the pairing calls needs `Authorization: Bearer <token>`.
Responses are JSON, `Cache-Control: no-store`. Only private-network clients are served.

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/health` | none | `{ok, app, version, hostId, name, port, ca}` |
| POST | `/api/pairing/claim` | `{hostId, secret, device}` | `{ok, hostId, token}` (QR invitation, single use) |
| POST | `/api/pairing/pin-claim` | `{hostId, pin, device}` | `{ok, hostId, token}` (PIN, single use) |
| GET | `/api/snapshot` | none | `{server_time, pc, stats, claude, alert, media, calendar, network, shortcuts, github}`; unavailable sections are `null` |
| GET | `/api/status` `/api/stats` `/api/claude` `/api/alerts` `/api/calendar` `/api/media` `/api/network` `/api/shortcuts` `/api/github` | none | `{available, data}` |
| POST | `/api/media` | `{"cmd":"toggle"|"next"|"prev"}` | `{ok}` |
| POST | `/api/shortcuts/run` | `{"id":0}` | `{ok}` |
| POST | `/api/alerts/action` | `{"id":"..","action":"dismiss"}` | `{ok}` |
| POST | `/api/speedtest` | `{}` | `{ok}` |
| POST | `/api/phone/status` | `{battery, charging, network, model}` | `{ok, status}` (shown on the PC) |
| GET | `/api/inbox` | none | `{items:[{id,kind,text}]}` messages from the PC |
| POST | `/api/inbox/ack` | `{"ids":[..]}` | `{ok}` |
| POST | `/api/send` | `{"kind":"clipboard"|"link","text":".."}` | `{ok}` (links must be http/https) |
| GET | `/api/remote` | none | `{allowed, battery}`; `allowed` is the PC owner's per-phone switch |
| POST | `/api/input` | `{"events":[{"t":"move","dx":5,"dy":-2},{"t":"click","button":"left"},{"t":"scroll","dy":3},{"t":"key","key":"enter","mods":["ctrl"]},{"t":"text","text":".."}]}` | `{ok,count}`; up to 64 events, strict limits; 403 unless allowed, 429 when too fast, 501 off Windows |
| POST | `/api/volume` | `{"action":"up"|"down"|"mute"}` | `{ok}` |
| POST | `/api/power` | `{"action":"sleep"|"restart"|"shutdown"|"abort"}` | `{ok, delaySeconds}` (restart and shutdown wait 15 s; `abort` cancels) |
| GET | `/api/processes` | none | `{items:[{pid,name,mem_mb}]}` |
| POST | `/api/processes/end` | `{"pid":1234}` | `{ok}`; system programs are refused |
| GET | `/api/files` | none | `{toPhone, received, maxBytes}` |
| PUT | `/api/files/upload?name=..` | file bytes | `{ok, name, size}` into `Documents\Hubuddy\Received` |
| GET | `/api/files/download?name=..` | none | file from `Documents\Hubuddy\ToPhone` |

The PC's own page (`http://127.0.0.1:8766/`) answers only to the PC itself (loopback address, loopback `Host`, and a
local-action header for changes) and is never reachable from the network. Errors are `{"error":".."}` with 400, 401, 403,
404, 405, 413, 429 or 501. Discovery: UDP port 41236, probe `hubuddy:discover:v1`, reply `hubuddy:here:v1:{json}`.

## 5. Troubleshooting

| In the app | Fix |
| --- | --- |
| "No PC found" | Same Wi-Fi/LAN (or USB tethering on)? Allow Hubuddy through the firewall for **private** networks (installer option, or the window's button). Guest Wi-Fi and some routers block device-to-device traffic: use a QR code or *Enter address*, or switch on USB tethering |
| "Cannot reach the PC" | Is Hubuddy running there? Same network? The address is re-found automatically when it changes |
| "This computer's identity does not match" | The PC was reinstalled or its identity folder was replaced. Remove it and pair again |
| "The invitation or PIN is wrong or has expired" | Invitations and PINs last 2 minutes and work once. Start pairing again on the PC |
| "Too many attempts" | Five wrong tries lock a phone for a minute (the PC also limits attempts overall). Wait and retry |
| AI page: "Claude Code is not connected" | No Claude Code data on that PC yet, or it is not paired |
| Limits and alerts missing | Run `python -m hubuddy_agent.claude_setup apply` |
| What's playing is missing | Needs the `winrt` packages (in `requirements.txt` on Windows) and something playing |
