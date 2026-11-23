# Releases

| File | Version | Android | Notes |
| --- | --- | --- | --- |
| [`Hubuddy-2.0.0.apk`](Hubuddy-2.0.0.apk) | 2.0.0 | 5.0+ (API 21) | Debug-signed build for sideloading. Not tested on a physical phone yet. |

Verify the download with the matching `.sha256` file (`sha256sum -c Hubuddy-2.0.0.apk.sha256`).

Install: open the APK on the phone and allow "install unknown apps" for your browser or file manager, or
use `adb install -r Hubuddy-2.0.0.apk`. This app has its own identity (`com.sidya.hubuddy`), so it installs
next to any earlier test build instead of replacing it; remove the older one if you do not need it.
