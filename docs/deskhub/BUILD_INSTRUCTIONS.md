# Build instructions — Hubuddy DeskHub (Android)

## Requirements

* **JDK 17+**. Android Studio's bundled JBR works; `build.ps1` finds it automatically.
* **Android SDK** with *Android SDK Platform 34* and *Build-Tools 34*. Android Studio installs
  these; `build.ps1` uses `%LOCALAPPDATA%\Android\Sdk` when `ANDROID_HOME` is not set.
* Internet on the first build, so Gradle can download its distribution and dependencies.

You do not need to edit any Gradle file.

## Windows (PowerShell)

```powershell
cd hubuddy\android\deskhub
.\build.ps1                 # unit tests + debug APK
.\build.ps1 -Release -Lint  # also the small R8 release APK and Android Lint
.\build.ps1 -SkipTests      # fastest
```

The script checks Java, the SDK and the Gradle wrapper, then runs the tests and builds the APK.
It verifies the APK exists, prints its exact path and size, and exits non-zero on any failure.
If PowerShell refuses to run scripts, allow them once with
`powershell -ExecutionPolicy Bypass -File .\build.ps1`.

## Manual Gradle commands

```powershell
cd hubuddy\android\deskhub
.\gradlew.bat testDebugUnitTest      # unit + Robolectric UI tests (Android 9 / API 28)
.\gradlew.bat assembleDebug          # -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat lintDebug              # -> app\build\reports\lint-results-debug.html
.\gradlew.bat assembleRelease        # -> app\build\outputs\apk\release\app-release.apk
```

On Linux or macOS, use `./gradlew` with the same tasks.

## Outputs

| File | Notes |
| --- | --- |
| `android/deskhub/app/build/outputs/apk/debug/app-debug.apk` | Primary deliverable (debug-signed, about 2.6 MB) |
| `android/deskhub/app/build/outputs/apk/release/app-release.apk` | R8-shrunk (about 140 KB), signed with your local debug key, for sideload testing only |

APKs are build outputs and are not committed (see `.gitignore`).

## Optional build settings (not committed)

To override the temporary first-login credentials baked into a build, put these in
`android/deskhub/local.properties`, or set the environment variables
`DESKHUB_BOOTSTRAP_LOGIN` and `DESKHUB_BOOTSTRAP_PIN`:

```properties
deskhub.bootstrapLogin=123456789
deskhub.bootstrapPin=123456
```

If Maven Central rate-limits you (HTTP 429) while Robolectric downloads its Android runtime,
point it at Google's mirror. Add this line to `%USERPROFILE%\.gradle\gradle.properties`, or
pass it as `-ProbolectricRepoUrl=...`:

```properties
robolectricRepoUrl=https://maven-central.storage-download.googleapis.com/maven2
```

## Install on the phone

1. On the Vivo, enable *Developer options → USB debugging*. To unlock Developer options, tap
   *Software version* (or *Build number*) seven times under Settings → About phone.
2. Connect USB, accept the RSA prompt, then:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

To record the device properties for the test report:

```powershell
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell wm size
adb shell wm density
adb shell df -h /data
```
