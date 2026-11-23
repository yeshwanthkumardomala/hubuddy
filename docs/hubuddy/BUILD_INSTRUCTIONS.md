# Build instructions (Android app)

## Requirements

* **JDK 17+** (Android Studio's bundled JBR works; `build.ps1` finds it).
* **Android SDK** with *Platform 34* and *Build-Tools 34* (Android Studio installs them; `build.ps1` uses
  `%LOCALAPPDATA%\Android\Sdk` when `ANDROID_HOME` is not set).
* Internet on the first build so Gradle can download its distribution and dependencies.

## Windows

```powershell
cd hubuddy\android\hubuddy
.\build.ps1                 # unit tests + debug APK
.\build.ps1 -Release -Lint  # also the small R8 release APK and Android Lint
.\build.ps1 -SkipTests      # fastest
```

The script checks Java, the SDK and the Gradle wrapper, runs the tests, builds the APK, verifies it exists,
prints its path and size, and exits non-zero on any failure. If PowerShell refuses to run scripts:
`powershell -ExecutionPolicy Bypass -File .\build.ps1`.

## Manual Gradle commands

```powershell
.\gradlew.bat testDebugUnitTest      # unit + Robolectric UI tests (Android 5.0 to 14)
.\gradlew.bat assembleDebug          # -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat lintDebug              # -> app\build\reports\lint-results-debug.html
.\gradlew.bat assembleRelease        # -> app\build\outputs\apk\release\app-release.apk
```

On Linux or macOS use `./gradlew`.

## First-login credentials

They are **not stored in the repository**. Your build bakes them in from, in order: `local.properties`
(`hubuddy.bootstrapLogin=` and `hubuddy.bootstrapPin=`), the environment variables `HUBUDDY_BOOTSTRAP_LOGIN` and
`HUBUDDY_BOOTSTRAP_PIN`, or — if neither is set — a random login number and PIN that the build prints
("Hubuddy first-login credentials generated for this build"). They are temporary: the app forces a new PIN on
first login. `local.properties` is git-ignored.

## Outputs

| File | Notes |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | Debug-signed, about 1 MB |
| `app/build/outputs/apk/release/app-release.apk` | R8-shrunk (about 120 KB), signed with your local debug key, for sideloading |

APKs are build outputs and are git-ignored; the project's published APK is in `releases/`.

## If Maven Central rate-limits you (HTTP 429)

Robolectric downloads Android runtime jars during tests. Point it at a mirror by adding this line to
`%USERPROFILE%\.gradle\gradle.properties` (or pass `-ProbolectricRepoUrl=...`):

```properties
robolectricRepoUrl=https://maven-central.storage-download.googleapis.com/maven2
```

## Install on the phone

Turn on *Developer options → USB debugging* (tap *Software version* or *Build number* seven times under About
phone), connect USB, accept the prompt, then:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Screenshots

`gradlew testDebugUnitTest --tests "*Screenshots*" -Pscreenshots=<folder>` renders the real screens with sample data.
