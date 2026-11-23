# Libraries

Hubuddy is original code. It uses these open-source libraries, each under its own license.

**Android app:** none at runtime (framework widgets, `HttpURLConnection` and `org.json` only). Tests use
JUnit (EPL 1.0) and Robolectric (MIT).

**PC agent** (installed from `agent/requirements.txt`; each is optional except `psutil` for system readings):

| Library | Used for | License |
| --- | --- | --- |
| [psutil](https://github.com/giampaolo/psutil) | CPU, memory, disk and network readings | BSD-3-Clause |
| [icalendar](https://github.com/collective/icalendar), [recurring-ical-events](https://github.com/niccokunzmann/python-recurring-ical-events) | Calendar feeds and repeating events | BSD-2-Clause / LGPL-3.0 |
| [winrt packages](https://github.com/pywinrt/pywinrt) (Windows only) | What is playing | MIT |
| tzdata | Time zone data | Apache-2.0 |

Weather and place search use the free [Open-Meteo](https://open-meteo.com) API (no key; attribution appreciated).

## Added in 3.0.0

| Library | Where | License | Why |
| --- | --- | --- | --- |
| ZXing core 3.5.3 | Android app (QR decoding only) | Apache 2.0 | reads the pairing QR code; the camera itself is the framework's |
| cryptography | PC program | Apache 2.0 / BSD | the per-PC certificate authority and server certificate |
| segno | PC program | BSD | draws the pairing QR code |
| PyInstaller | build only | GPL with bootloader exception | builds `Hubuddy.exe` |
| Inno Setup | build only | Inno Setup License | builds `HubuddySetup.exe` |
