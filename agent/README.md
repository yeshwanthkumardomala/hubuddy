# Hubuddy PC program

Runs on a PC or laptop and serves its live readings to the Hubuddy Android app over an encrypted, pinned HTTPS API:
CPU, memory, disk, GPU (NVIDIA), network throughput, internet health, Claude Code usage and alerts, what is playing,
calendar events, GitHub contributions and your shortcut buttons. It also exchanges text, links, the clipboard and files
with paired phones and shows each phone's battery and network.

* Python 3.11+. Most people use `Hubuddy.exe` / `HubuddySetup.exe` from the Releases page instead.
* Phones pair with a **QR code** or a **PIN** (with a compare code) and find the PC by themselves on the same Wi-Fi/LAN
  or USB tethering. No developer options, no IP typing. Several phones and several PCs work together.
* A private certificate authority per installation is pinned by each phone; tokens are stored only as hashes.
* The phone sends fixed actions (media control, a shortcut number, dismiss an alert, send text/link/file). What a shortcut
  does is written in `config.toml` on the PC; commands are started without a shell.

```powershell
.\start-agent.ps1 -Window    # the Hubuddy window
.\start-agent.ps1 -Invite    # print a pairing QR code here
python -m hubuddy_agent --selftest
```

Claude Code limits and "waiting for permission" alerts: `python -m hubuddy_agent.claude_setup show` (preview), then
`apply` (adds a hook and status line to `~/.claude/settings.json`, with a backup; `remove` undoes it).

Tests: `PYTHONPATH=tests:. python -m unittest discover -s tests`. Setup and API reference: [docs](../docs/hubuddy/API_SETUP.md).
