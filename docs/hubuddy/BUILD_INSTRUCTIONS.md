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

Copy `app-debug.apk` (or the release APK) to the phone, open it, and allow *install unknown apps* for the app you opened
it from. No developer options or cable are needed. (If you prefer a cable, `adb install -r` still works, but nothing in
Hubuddy requires it.)

## Windows program and installer

Built by GitHub Actions on a real Windows runner (`.github/workflows/build.yml`): `pyinstaller agent/hubuddy.spec` makes
`Hubuddy.exe`, Inno Setup (`installer/Hubuddy.iss`) makes `HubuddySetup.exe`, and the workflow runs the tests, a self-test
of the portable and the installed program, a silent install/uninstall, and (for a `v*` tag) publishes a Release.
Locally on Windows: `pip install -r agent/requirements.txt pyinstaller`, then `pyinstaller --noconfirm agent/hubuddy.spec`.
`Hubuddy.exe --selftest` pairs a pretend phone with the program itself and prints a pass/fail list.

## Interop test

`tools/interop.sh` starts the real PC program and runs the app's real client code against it (QR and PIN pairing, API,
messages, files). Needs Python with `agent/requirements.txt`, JDK 17 and the Android SDK.

## Python-source download

`python tools/package_agent.py` builds `releases/Hubuddy-Agent-<version>.zip` and its checksum from `agent/`
(without tests, caches or your `config.toml`). Same sources give the same zip.

## Screenshots

`gradlew testDebugUnitTest --tests "*Screenshots*" -Pscreenshots=<folder>` renders the real screens with sample data.
