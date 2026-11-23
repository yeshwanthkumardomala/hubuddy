# Hubuddy agent

Runs on a PC or laptop and serves its live readings to the Hubuddy Android app over an authenticated local HTTP
API (port 8765): CPU, memory, disk, GPU (NVIDIA), network throughput, internet health, Claude Code usage and alerts,
what is playing, calendar events, GitHub contributions and your shortcut buttons.

* Python 3.11+. `psutil` for system stats; everything else is optional (see `requirements.txt`).
* Listens on `127.0.0.1` by default (USB, via `adb reverse`). `--lan` makes it reachable on your local network,
  accepting only private-network clients. Plain HTTP is not encrypted.
* A one-time pairing code gives each phone its own token (stored only as a hash on the PC).
* The phone sends fixed actions (media control, a shortcut number, dismiss an alert). What a shortcut does is
  written in `config.toml` on the PC; commands are started without a shell.

```powershell
.\start-agent.ps1            # USB mode
.\start-agent.ps1 -Lan       # Wi-Fi mode
```

Claude Code limits and "waiting for permission" alerts: `python -m hubuddy_agent.claude_setup show`
(preview), then `apply` (adds a hook and status line to `~/.claude/settings.json`, with a backup; `remove` undoes it).

Tests: `python -m unittest discover -s tests`. Setup and API reference: [docs](../docs/hubuddy/API_SETUP.md).
