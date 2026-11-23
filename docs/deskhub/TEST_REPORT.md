# Test report — Hubuddy DeskHub 1.3.0

Date: 2026-11-23 · Environment: Linux build container (cloud), OpenJDK 21, Gradle 8.14.3,
AGP 8.7.3, Kotlin 2.0.21, Android SDK Platform 34 / Build-Tools 34, Robolectric 4.14.1, Python 3.13.

> **Physical Vivo 1902 testing: PENDING.** No phone was attached (`adb devices -l` listed no
> devices), and this container has no KVM, so no Android emulator could run. Android 9
> behaviour was tested with Robolectric on **Android 5.0, 5.1, 7.0, 8.0, 9, 11 and 14**
> (API 21, 22, 24, 26, 28, 30, 34). Nothing here claims the APK
> has run on the Vivo 1902.

## Summary

| Suite | Result |
| --- | --- |
| Compilation (`assembleDebug`, `assembleRelease`) | ✅ BUILD SUCCESSFUL |
| Android unit + Robolectric UI tests (`testDebugUnitTest`) | ✅ **174 run, 0 failed, 0 skipped** (includes 12 screenshot renders) |
| Contrast of every colour pair in Dark, Black and Light | ✅ `PaletteTest`: text 7:1, secondary text and button labels 4.5:1, accent and status colours 3:1 |
| Android version coverage (UI tests) | ✅ API 21 (13 tests), 22 (1), 24, 26, 28, 30, 34 (12 each): all passed |
| Android Lint (`lintDebug`, includes manifest checks) | ✅ 0 errors; `NewApi` clean for minSdk 21. 2 expected warnings: `InsecureBaseConfiguration` (cleartext HTTP to the LAN companion, by design and restricted in code) and `UnusedAttribute` (`networkSecurityConfig` is ignored below Android 7, which allows that traffic anyway) |
| APK inspection (`aapt2 dump badging`) | ✅ package `com.sidya.deskhub`, minSdk **21**, targetSdk 34, permissions INTERNET + ACCESS_NETWORK_STATE only, no native code |
| Bridge tests (`python -m unittest`, companion-bridge) | ✅ **13 passed** |
| Bridge ↔ real DeskHub companion (Linux smoke test) | ✅ snapshot carried live CPU/RAM/disk/network stats, Claude usage, calendar and 8 shortcut tiles |
| Web dashboard in headless Chromium (360 px wide) | ✅ paired, rendered live data, no JS errors, no horizontal scroll |
| PowerShell scripts (`build.ps1`, `start-bridge.ps1`) | ⚠️ **Not executed**: PowerShell is not available in this container |
| Physical device / emulator | ⏳ **Not run** (see above) |

## Required checks

| # | Check | How it was tested | Result |
| --- | --- | --- | --- |
| 1 | Project compilation | `gradlew assembleDebug assembleRelease` | ✅ |
| 2 | APK generation | `app/build/outputs/apk/debug/app-debug.apk` (956,974 bytes); release 79,355 bytes | ✅ |
| 3 | Manifest validation | Lint + `aapt2 dump badging` | ✅ |
| 4 | Login screen | `UiFlowTest.loginScreenShowsFieldsAndMasksPin` | ✅ (this test found a real bug, PIN not masked, which was fixed) |
| 5 | Correct initial login | `UiFlowTest.firstLoginForcesPinChangeThenOpensDashboard`, `AuthManagerTest.bootstrapLogin…` | ✅ |
| 6 | Incorrect PIN rejection | `UiFlowTest.wrongPinIsRejected`, `AuthManagerTest.wrongPinAndWrongLoginRejected`, lockout tests | ✅ |
| 7 | First-login PIN change | `UiFlowTest.firstLogin…` (dashboard blocked until changed; temporary PIN refused as new PIN), `AuthManagerTest.changePinFlow` | ✅ |
| 8 | Authentication persistence | `AuthManagerTest.authPersistsAcrossAppRestart`, `lockoutGrowsAndPersistsAcrossRestart` | ✅ |
| 9 | Dashboard loading | `UiFlowTest.dashboardShowsLiveCompanionData` (fake companion over real HTTP) | ✅ |
| 10 | Navigation | `UiFlowTest.bottomNavigationOpensAllFiveTabs`, `secondaryPagesOpenAndBackReturns` | ✅ |
| 11 | Offline state | `UiFlowTest.dashboardOpensOfflineWithClockAndClearStates`, `DashboardRepositoryTest.dashboardLoadsAndIsCachedForOffline` | ✅ |
| 12 | API timeout | `DashboardRepositoryTest.timeoutIsReportedNotThrown` | ✅ |
| 13 | API reconnection | `DashboardRepositoryTest.reconnectsAfterOutage`, `backoffGrowsAndCaps` | ✅ |
| 14 | Invalid server address | `AddressValidatorTest.*`, `invalidOrPublicAddressRejectedBeforeAnyRequest`, `UiFlowTest.settingsRejectsPublicAddress…`, `nothingListeningIsUnreachable` | ✅ |
| 15 | Configuration persistence | `SettingsTest.persistsAndClamps`, `UiFlowTest.settingsRejectsPublicAddressAndPersistsValidOne` | ✅ |
| 16 | Android 9 compatibility (and Android 5.0+ in general) | minSdk 21; every UI test on API 21/24/26/28/30/34; `OldAndroidCompatTest` on API 21/22 without Keystore; Lint `NewApi` clean | ✅ in simulation · ⏳ real devices pending |
| 17 | Crash / exception handling | `UiFlowTest.malformedPayloadDoesNotCrash`, `connectionTestToDeadCompanionShowsErrorWithoutCrash`, `rotationKeepsDashboard` | ✅ |

## Android tests run (106 = 34 JVM tests + 12 UI tests × 6 Android versions + 1 compat test × 2 versions)

* **AuthManagerTest (11):** bootstrap login requires PIN change · PIN never stored in plaintext ·
  wrong PIN / wrong login rejected · lockout after 5 failures, then expiry · lockout grows and
  persists across restart · change-PIN flow (wrong current, mismatch, weak, same-as-temporary,
  success, temporary PIN dead afterwards) · change needs a session · persistence across
  restart · inactivity timeout · logout · clock moved back cannot lock forever.
* **PinHasherTest (5):** salted and verifiable · rejects malformed hashes · PIN policy · matches the
  JDK's PBKDF2WithHmacSHA256 · RFC 7914 test vector.
* **OldAndroidCompatTest (API 21, 22):** no Keystore → in-memory secrets, login + PIN change +
  dashboard + legacy network API work, and no secret is written to preferences.
* **AddressValidatorTest (2), SettingsTest (2):** local addresses accepted; public, malformed
  and non-HTTP rejected; settings persist and clamp; phone shortcut parsing rejects
  `javascript:` and other schemes.
* **DashboardRepositoryTest (12):** against a real local HTTP server: ping without pairing ·
  pairing stores token / wrong code fails · not-paired state · live load + disk cache + cached
  offline display after restart · timeout · reconnect after outage · bad token → unauthorized ·
  public address blocked before any request · connection refused → unreachable · authenticated
  POST commands · backoff · clear cache.
* **UiFlowTest (12 × 6 Android versions):** login masking + show/hide · wrong PIN · first login
  → forced PIN change → dashboard · offline dashboard with clock and banner, no fake values ·
  all five tabs · secondary pages + back · settings address validation + persistence ·
  connection test to a dead port shows an error · logout · rotation · live companion data
  rendered on Home/Monitor/AI/Controls · malformed payload on every tab without a crash.

## Bridge tests run (13)

Ping leaks only service and version · every data endpoint requires a token · pair → snapshot ·
token stored only as SHA-256 · pairing code is single use · code closes after 5 wrong guesses ·
bad-token rate limit (429) · command validation (bad verbs, out-of-range or wrong-type ids
rejected; only valid commands reach DeskHub) · malformed JSON / non-object / oversize body ·
revoke · static web dashboard served with security headers · path traversal blocked · garbage
request line · peer filter (private allowed, public refused).

## Desk mode tests (DeskModeTest, on Android 5.0, 9 and 14)

Starts after the idle time only while charging (or when allowed on battery) · Desk button · offline
shows only the clock · rotation visits every page with data and draws the heatmap · advances after
the configured seconds · a Claude permission alert jumps to the Claude page · night shows a dim
clock only at minimum brightness · touch leaves and keeps a valid session · an expired session
needs the PIN again · malformed data never crashes it · night hours wrap midnight, settings clamp.
Not covered: real screen-off behaviour, real battery and charger events, and how it looks on a real phone.

## 1.3.0 tests (multi-PC, themes, layout)

* **PcRegistryTest (4):** up to 8 PCs, limit enforced · separate token per PC, removed with it, last PC
  cannot be removed · persistence and name limits · migration of the single-PC setup of earlier versions.
* **MultiPcUiTest (6, Android 9):** three PCs polled together (two online, one dead) and shown as tiles ·
  tapping a tile selects that PC · an offline PC does not block the others · adding, editing, duplicate
  name refusal and removing PCs in Settings · switching to Light and back · landscape shows the rail and 2
  columns, portrait shows the bottom bar and 1 column.
* **DeskModeTest (+3):** starts on battery by default but not at 12% · stops itself when the battery drops
  to 10% · the All PCs page appears only with several PCs.
* **PaletteTest (2), SettingsTest (+1):** colour contrast, theme persistence, old "charcoal" mapped to Dark.
* The Home screen test now asserts there is no big clock.

Layout was also checked by eye in rendered screenshots (portrait and landscape, dark and light, 4 PCs),
which led to the compact PC tiles and the two-column settings page.

## Not run / pending

1. **Physical Vivo 1902**: install, launch, Keystore on the real device, real `adb reverse`, Wi-Fi
   mode, screen size and density, battery behaviour. Pending the phone.
2. **Android emulator / instrumentation tests**: not possible without KVM here. Robolectric
   covered the flows above.
3. **`build.ps1` / `start-bridge.ps1`**: written for Windows PowerShell 5.1 and 7, but not executed.
4. **Bridge on Windows with the real companion**: verified on Linux with Windows-only modules
   shimmed in a scratch harness (registry, media session, `C:\` drive). Windows media, GPU (NVML),
   registry CPU name, shortcuts and alert focus need a run on the PC.
5. **Android 5.0–7.0 HTTPS to weather/GitHub**: these phones may not trust newer certificate
   authorities; the app then shows "unavailable" for those cards. PC data is unaffected.
6. **Release APK at runtime**: R8 build succeeds; tests ran against the unshrunk debug build.
   The app uses no reflection, so shrinking risk is low.

## Suggested device checklist (when the phone is connected)

```powershell
adb devices -l; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk; adb shell getprop ro.product.cpu.abilist
adb shell wm size; adb shell wm density; adb shell df -h /data
adb install -r android\deskhub\app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8765 tcp:8765
adb logcat -s AndroidRuntime:E   # watch for crashes while exercising the app
```

Then repeat the 17 checks above by hand: login, wrong PIN, PIN change, every tab, airplane mode
(offline banner and cached data), stop the bridge (reconnect), wrong address, rotate, and
restart the app.
