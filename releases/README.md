# Releases

Everything you need to install Hubuddy. You need **both**: the app on your phone, and the agent on each PC or laptop.

| Download | What it is | Where it runs | Size | Checksum |
| --- | --- | --- | --- | --- |
| [**Hubuddy-2.0.0.apk**](Hubuddy-2.0.0.apk) | The phone app | Android 5.0 or newer | about 1 MB | [sha256](Hubuddy-2.0.0.apk.sha256) |
| [**Hubuddy-Agent-2.0.0.zip**](Hubuddy-Agent-2.0.0.zip) | The laptop / PC agent | Windows 10 or 11 with Python 3.11+ (also runs on other systems, with fewer features) | about 40 KB | [sha256](Hubuddy-Agent-2.0.0.zip.sha256) |

Verify a download: `sha256sum -c Hubuddy-2.0.0.apk.sha256` (Windows: `certutil -hashfile <file> SHA256` and compare).

## Install the phone app

Open the APK on the phone and allow "install unknown apps" for your browser or file manager, or run
`adb install -r Hubuddy-2.0.0.apk`. The debug-signed build is for sideloading. The app has its own identity
(`com.sidya.hubuddy`), so it installs next to any earlier test build.

## Install the laptop agent

1. Install **Python 3.11 or newer** from python.org (tick "Add python.exe to PATH") if you do not have it.
2. Unzip `Hubuddy-Agent-2.0.0.zip` anywhere (for example `C:\Hubuddy`).
3. Double-click **`start-agent.bat`**. The first start installs what it needs (about a minute), then prints a
   6-digit pairing code.
4. In the app: Settings → PCs and laptops → enter the code → Pair.

Repeat on every computer you want to see (up to 8). For Wi-Fi instead of USB, start it with `start-agent.bat -Lan`.
The agent is a Python program, not a single `.exe`: a standalone installer has to be built on Windows. Full guide:
[agent setup](../docs/hubuddy/API_SETUP.md).

## Rebuilding the downloads

* Phone app: `android/hubuddy/build.ps1` (see the [build instructions](../docs/hubuddy/BUILD_INSTRUCTIONS.md)).
* Laptop agent zip: `python tools/package_agent.py` (identical output every time for the same sources).
