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
