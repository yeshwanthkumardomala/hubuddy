# Test report — Hubuddy DeskHub 1.0.0

Date: 2026-11-22 · Environment: Linux build container (cloud), OpenJDK 21, Gradle 8.14.3,
AGP 8.7.3, Kotlin 2.0.21, Android SDK Platform 34 / Build-Tools 34, Robolectric 4.14.1, Python 3.13.

> **Physical Vivo 1902 testing: PENDING.** No phone was attached (`adb devices -l` listed no
> devices), and this container has no KVM, so no Android emulator could run. Android 9
> behaviour was tested with Robolectric configured for **API 28**. Nothing here claims the APK
> has run on the Vivo 1902.

## Summary

| Suite | Result |
| --- | --- |
| Compilation (`assembleDebug`, `assembleRelease`) | ✅ BUILD SUCCESSFUL |
| Android unit + Robolectric UI tests (`testDebugUnitTest`) | ✅ **42 passed, 0 failed, 0 skipped** |
| Android Lint (`lintDebug`, includes manifest checks) | ✅ 0 errors, 1 expected warning (`InsecureBaseConfiguration`: cleartext HTTP to the LAN companion, by design and restricted in code) |
| APK inspection (`aapt2 dump badging`) | ✅ package `com.sidya.deskhub`, minSdk 28, targetSdk 34, permissions INTERNET + ACCESS_NETWORK_STATE only, no native code |
| Bridge tests (`python -m unittest`, companion-bridge) | ✅ **13 passed** |
| Bridge ↔ real DeskHub companion (Linux smoke test) | ✅ snapshot carried live CPU/RAM/disk/network stats, Claude usage, calendar and 8 shortcut tiles |
| Web dashboard in headless Chromium (360 px wide) | ✅ paired, rendered live data, no JS errors, no horizontal scroll |
| PowerShell scripts (`build.ps1`, `start-bridge.ps1`) | ⚠️ **Not executed**: PowerShell is not available in this container |
| Physical device / emulator | ⏳ **Not run** (see above) |

## Required checks

| # | Check | How it was tested | Result |
| --- | --- | --- | --- |
| 1 | Project compilation | `gradlew assembleDebug assembleRelease` | ✅ |
| 2 | APK generation | `app/build/outputs/apk/debug/app-debug.apk` (2,671,521 bytes); release 144,206 bytes | ✅ |
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
| 16 | Android 9 compatibility | minSdk 28; Robolectric `@Config(sdk=[28])`; Lint `NewApi` clean; only APIs ≤ 28 used without guards | ✅ assessed in simulation · ⏳ device pending |
| 17 | Crash / exception handling | `UiFlowTest.malformedPayloadDoesNotCrash`, `connectionTestToDeadCompanionShowsErrorWithoutCrash`, `rotationKeepsDashboard` | ✅ |

## Android tests run (42)

* **AuthManagerTest (11):** bootstrap login requires PIN change · PIN never stored in plaintext ·
  wrong PIN / wrong login rejected · lockout after 5 failures, then expiry · lockout grows and
  persists across restart · change-PIN flow (wrong current, mismatch, weak, same-as-temporary,
  success, temporary PIN dead afterwards) · change needs a session · persistence across
  restart · inactivity timeout · logout · clock moved back cannot lock forever.
* **PinHasherTest (3):** salted and verifiable · rejects malformed hashes · PIN policy.
* **AddressValidatorTest (2), SettingsTest (2):** local addresses accepted; public, malformed
  and non-HTTP rejected; settings persist and clamp; phone shortcut parsing rejects
  `javascript:` and other schemes.
* **DashboardRepositoryTest (12):** against a real local HTTP server: ping without pairing ·
  pairing stores token / wrong code fails · not-paired state · live load + disk cache + cached
  offline display after restart · timeout · reconnect after outage · bad token → unauthorized ·
  public address blocked before any request · connection refused → unreachable · authenticated
  POST commands · backoff · clear cache.
* **UiFlowTest (12, Robolectric, API 28):** login masking + show/hide · wrong PIN · first login
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

## Not run / pending

1. **Physical Vivo 1902**: install, launch, Keystore on the real device, real `adb reverse`, Wi-Fi
   mode, screen size and density, battery behaviour. Pending the phone.
2. **Android emulator / instrumentation tests**: not possible without KVM here. Robolectric
   covered the flows above.
3. **`build.ps1` / `start-bridge.ps1`**: written for Windows PowerShell 5.1 and 7, but not executed.
4. **Bridge on Windows with the real companion**: verified on Linux with Windows-only modules
   shimmed in a scratch harness (registry, media session, `C:\` drive). Windows media, GPU (NVML),
   registry CPU name, shortcuts and alert focus need a run on the PC.
5. **Release APK at runtime**: R8 build succeeds; tests ran against the unshrunk debug build.
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
