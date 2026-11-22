# Hubuddy DeskHub bridge

Serves the [DeskHub](https://github.com/zacharyinserra/deskhub) companion's PC data (stats,
media, Claude Code usage and alerts, calendar, internet, GitHub, shortcuts) to the Hubuddy
DeskHub Android app over an authenticated local HTTP API on port 8765.

* Standard-library Python (3.11+); runs inside the DeskHub companion's `uv` environment.
* Reuses DeskHub's code by subclassing its `Companion` and does not modify DeskHub.
* Binds 127.0.0.1 by default (USB / `adb reverse`); `--lan` for Wi-Fi, private clients only.
* One-time pairing code → per-device bearer token (stored hashed); validated commands only.

```powershell
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion        # USB
.\start-bridge.ps1 -Companion C:\path\to\deskhub\companion -Lan   # Wi-Fi
```

Tests: `python -m unittest discover -s tests`

Full setup and API reference: [docs/deskhub/API_SETUP.md](../docs/deskhub/API_SETUP.md).
