# Releases

Everything you need to install Hubuddy: the app on your phone and the program on each PC or laptop.
The same files are on the [GitHub Releases page](https://github.com/yeshwanthkumardomala/hubuddy/releases).

| Download | What it is | Runs on |
| --- | --- | --- |
| **Hubuddy-3.0.0.apk** | the phone app | Android 5.0 or newer |
| **HubuddySetup-3.0.0.exe** | installer for the PC program (Start menu, optional firewall rule and autostart) | Windows 10 or 11 |
| **Hubuddy-3.0.0.exe** | the same program, portable (no install) | Windows 10 or 11 |

Each file has a `.sha256` next to it. Verify: `certutil -hashfile <file> SHA256` (Windows) or `sha256sum -c <file>.sha256`.

Older: `Hubuddy-2.0.0.apk` and `Hubuddy-Agent-2.0.0.zip` are the previous version (USB/adb pairing); they do not work with 3.x.

## Install

1. **PC:** run `HubuddySetup-3.0.0.exe` (or `Hubuddy-3.0.0.exe`). Windows may say "unknown publisher" because the program is
   not code-signed: *More info → Run anyway*.
2. **Phone:** open the APK and allow *install unknown apps*.
3. **Pair:** on the PC click *Pair a phone*; in the app choose Settings → Add a PC or laptop → *Scan QR code*. No camera: use
   *Pair with a PIN* on the PC and *Find PCs on this network* in the app. Same Wi-Fi/LAN or USB tethering; no developer options.

The `.exe` files and the APK are built by [CI](../.github/workflows/build.yml) on a real Windows/Linux runner, tests passing, and
attached to each release. Build them yourself: see the [build instructions](../docs/hubuddy/BUILD_INSTRUCTIONS.md).
