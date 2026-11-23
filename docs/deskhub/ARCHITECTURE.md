# Hubuddy DeskHub — SIDYA Edition: Architecture

Hubuddy DeskHub turns an Android 9 phone (target: Vivo 1902) into a desk dashboard for a
Windows 11 PC. It is an Android adaptation of [DeskHub](https://github.com/zacharyinserra/deskhub)
(MIT, © Desk Hub contributors), which originally drives an ESP32-S3 touch display.

```
┌──────────── Android phone (com.sidya.deskhub) ────────────┐        ┌──────────── Windows PC ─────────────────────────────┐
│ LoginActivity ─► ChangePinActivity ─► MainActivity         │        │ hubuddy_bridge (companion-bridge/)                  │
│   AuthManager (PBKDF2 PIN, lockout, session timeout)       │  HTTP  │   BridgeServer  :8765  (auth, validation, limits)   │
│   MainActivity: Home | Monitor | AI | Controls | Settings  │◄──────►│   DeskhubSource ── subclasses DeskHub's Companion   │
│   DashboardRepository: poll /api/snapshot, backoff, cache  │ Bearer │        │ stats · media · Claude usage · alerts     │
│   CompanionLink + ApiClient (HttpURLConnection, org.json)  │ token  │        │ calendar · internet · GitHub · shortcuts  │
│   SecretStore: AES-GCM key in Android Keystore             │        │   (optional) also streams to the ESP32 board        │
│   Open-Meteo weather, GitHub repo info (direct, internet)  │        │   web/  optional browser dashboard (same API)       │
└────────────────────────────────────────────────────────────┘        └─────────────────────────────────────────────────────┘
        USB:  phone 127.0.0.1:8765 ──adb reverse──► PC 127.0.0.1:8765
        Wi-Fi: phone ──► http://<PC LAN IP>:8765 (bridge started with --lan)
```

## What was found in DeskHub, and how it was adapted

| DeskHub piece | Role there | In Hubuddy DeskHub |
| --- | --- | --- |
| `firmware/` (ESP-IDF + LVGL) | UI on the ESP32 board; runs the WebSocket **server** | **Not used.** Replaced by the native Android app. |
| `companion/deskhub` (Python) | WebSocket **client**: connects *to* the board and pushes JSON (`stats`, `media`, `ai`, `alert`, `events`, `net`, `github`, `tiles`…) | **Reused unmodified.** `DeskhubSource` subclasses its `Companion`, captures every message it would send, and feeds phone commands into its own `handle()`. |
| Board → PC commands (`media`, `shortcut` by index, `alert_action`, `speedtest`) | Board never holds commands, only button indexes | Same model: the phone sends a verb + index; the PC looks up what to run. |
| Weather (board fetches Open-Meteo itself) | No PC needed | Same: the phone calls Open-Meteo directly, so weather works with the PC off. |

The original companion does **not** listen on any port (it dials the board), so port 8765 is
served by the new bridge. The bridge does not change DeskHub's files and can run next to, or
instead of, the board (`--also-board` keeps the ESP32 display working from the same process).

## Android app (`android/deskhub`)

* **No AndroidX, Material or OkHttp.** Framework widgets, `HttpURLConnection` and the built-in
  `org.json`. Debug APK about 0.95 MB, R8 release APK about 80 KB. No native code, so it runs on
  any CPU type.
* **Runs on Android 5.0+ (minSdk 21).** Nothing requires a newer API without a fallback:
  PBKDF2-SHA256 is implemented on HmacSHA256 (the platform version needs Android 8), Base64
  comes from `android.util`, and network type uses `activeNetworkInfo` before Android 6. On
  Android 5.x, which has no AES keys in the Keystore, secrets stay in memory only (you re-pair
  after an app restart) and are never written as plain text.
* **Views built in code** (`ui/Ui.kt`) with a dark charcoal or pure-black palette, walnut
  hairline accents, teal highlights and no animations.
* **Single activity, five tabs**, with secondary pages pushed on a small back stack:
  * Home: clock, date, PC, network, Claude Code, next event, weather, now playing
  * Monitor: PC status, CPU, RAM, disk, GPU, VRAM, temperatures and throughput
    * Network: phone network, companion latency, PC internet and speed test
    * GitHub: repository info and contributions
  * AI: Claude Code session state, waiting-for-permission alert with focus/dismiss, 5-hour and weekly limits, tokens, 7-day chart
  * Controls: media, PC shortcuts (confirmed before running), phone shortcuts (URLs and apps)
  * Settings: connection, pairing, refresh and timeouts, offline behaviour, weather location, GitHub, shortcuts, theme, session, about
  * Calendar and Weather detail pages open from the Home cards.
* **Never fakes data.** A value the companion did not send shows "unavailable". With the PC
  offline, the last saved snapshot is shown under a banner with its age. This can be turned off
  in Settings.

### Data flow

`DashboardRepository` polls `GET /api/snapshot` every *refresh* seconds (default 5) and only
while `MainActivity` is resumed; `onPause` stops all polling. On errors it backs off
exponentially (refresh × 2ⁿ, max 60 s) and resets on success. A good snapshot is written
atomically to `cacheDir/snapshot.json` at most every 30 s. Weather and GitHub run on a separate
thread so a slow internet connection never delays PC data.

### Security

| Concern | Measure |
| --- | --- |
| App lock | Login number + PIN. PIN stored only as PBKDF2-HMAC-SHA256 (60 000 iterations, 16-byte random salt), compared in constant time. |
| Bootstrap credentials | `123456789` / `123456` are **temporary**: the first login forces a PIN change, the dashboard is unreachable until then, and the temporary PIN is refused as the new one. They come from `BuildConfig` and can be overridden at build time (`local.properties` or env). |
| Brute force | 5 failures → 30 s lock, doubling per round up to 15 min; persisted across restarts. |
| Session | Random session id encrypted in the Keystore-backed store; inactivity timeout (default 15 min, 1–240). Checked every second and on resume. |
| Secrets at rest | Companion token, GitHub token and session id use AES-256-GCM with a non-exportable Android Keystore key. If the Keystore is unusable, secrets stay **in memory only** (never plaintext on disk). `allowBackup=false`. |
| Screens | `FLAG_SECURE` on login and PIN screens. No credentials are logged anywhere. |
| Companion auth | Independent of the phone lock. A single-use, expiring 6-digit pairing code (invalidated after 5 wrong guesses) yields a 256-bit device token; the PC stores only its SHA-256. Every data and command endpoint requires `Authorization: Bearer`. |
| Network exposure | Bridge binds 127.0.0.1 by default. `--lan` must be explicit, and even then non-private client addresses get 403. The app refuses to send its token to non-local addresses (`AddressValidator`). Failed auth is rate-limited per client. |
| Commands | Fixed verbs with validated arguments (`toggle/next/prev`, shortcut index range-checked, alert `focus/dismiss`). No command strings travel over the network. The app asks for confirmation before running PC shortcuts and speed tests. |
| Transport | Plain HTTP is **not encrypted**. USB mode keeps traffic on the cable. In Wi-Fi mode use a trusted network. |

## Several PCs, themes and layout (1.3.0)

* **PCs:** `PcRegistry` keeps up to 8 `PcProfile`s (name, USB or Wi-Fi, address). Each PC has its own
  pairing token in the Keystore-encrypted store (`pc.<id>.token`). `DashboardRepository` runs one
  independent poller per PC on a small thread pool, each with its own back-off and
  `snapshot_<id>.json` offline cache. `DashState` mirrors the *selected* PC in its plain fields (so Home,
  AI and Controls stay simple) and carries `pcs` for the overview screens (All PCs, Home summary, desk
  All PCs page). USB (`adb reverse`) maps one cable to one PC, so additional PCs use Wi-Fi.
* **Themes:** `Pal` holds the colours (Dark, Black, Light, Auto). Views read it when they are built; a
  theme change rebuilds the frame. A test checks contrast of every colour pair in every look.
* **Layout:** `MainActivity` builds a portrait frame (header + bottom bar) or a landscape frame (rail +
  content). `Screen.columns` flows cards into 1 to 4 columns by width, placing each card in the
  currently shortest column. There is no clock on the everyday screens; it lives in desk mode.

## Bridge API (summary — details in API_SETUP.md)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/ping` | none | Reachability; returns service and version only |
| POST | `/api/pair` | pairing code | `{code, device}` → `{token}` |
| GET | `/api/snapshot` | token | Everything below in one response (what the app polls) |
| GET | `/api/status` `/api/stats` `/api/claude` `/api/calendar` `/api/media` `/api/network` `/api/shortcuts` `/api/github` `/api/alerts` | token | One section: `{available, data}` |
| GET | `/api/weather` | token | `{available:false}`; the phone fetches weather itself |
| POST | `/api/media` `/api/shortcuts/run` `/api/alerts/action` `/api/speedtest` | token | Commands |

WebSocket push was considered. Polling a single compact snapshot is simpler, survives Wi-Fi
drops, needs no long-lived socket on the phone, and stops completely in the background.
WebSocket is a possible future addition.

## Repository layout

```
android/deskhub/        Android app (Gradle, Kotlin) + build.ps1
companion-bridge/       PC bridge (stdlib Python), start-bridge.ps1, tests, optional web/ dashboard
docs/deskhub/           ARCHITECTURE, BUILD_INSTRUCTIONS, API_SETUP, TEST_REPORT, RELEASE_NOTES, THIRD_PARTY_NOTICES
```
