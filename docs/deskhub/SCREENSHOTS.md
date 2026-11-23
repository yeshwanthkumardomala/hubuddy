# Screenshots

**Phone app:** rendered from the real app code with Robolectric (Android 14 runtime, 720 × 1520 px,
close to the Vivo 1902 screen). Sample data comes from a fake PC companion; these are not photos of
a physical phone. To regenerate them:
`gradlew testDebugUnitTest --tests "*Screenshots*" -Pscreenshots=<folder>`

| | | |
| --- | --- | --- |
| ![Login](screenshots/phone/01-login.png) Login | ![Set PIN](screenshots/phone/02-set-new-pin.png) First-login PIN change | ![Home](screenshots/phone/03-home.png) Home |
| ![Monitor](screenshots/phone/04-monitor.png) Monitor | ![Network](screenshots/phone/05-network.png) Network | ![GitHub](screenshots/phone/06-github.png) GitHub |
| ![AI](screenshots/phone/07-ai-claude-code.png) AI / Claude Code | ![Controls](screenshots/phone/08-controls.png) Controls | ![Calendar](screenshots/phone/09-calendar.png) Calendar |
| ![Weather](screenshots/phone/10-weather.png) Weather | ![Offline](screenshots/phone/11-home-offline-not-paired.png) Offline / not paired | ![Settings](screenshots/phone/12-settings.png) Settings |

![Landscape](screenshots/phone/13-landscape-monitor-offline.png)
Landscape (two columns)

## Desk mode (phone standing beside your PC, landscape)

Starts by itself after 5 minutes without a touch while the phone is charging, or with the **Desk**
button. It shows the clock, then each page in turn (every 12 s by default), skipping pages that have no
data. A new Claude "waiting for permission" alert jumps straight to its page. At night, or while the
PC's screen sleeps, only a dim clock stays on. Touch anywhere to leave.

| | | |
| --- | --- | --- |
| ![Clock](screenshots/phone/desk-07-clock.png) Clock | ![PC](screenshots/phone/desk-08-pc.png) PC | ![Claude](screenshots/phone/desk-01-claude.png) Claude Code |
| ![Media](screenshots/phone/desk-02-media.png) Media | ![Calendar](screenshots/phone/desk-03-calendar.png) Calendar | ![Weather](screenshots/phone/desk-04-weather.png) Weather |
| ![Network](screenshots/phone/desk-05-network.png) Network | ![GitHub](screenshots/phone/desk-06-github.png) GitHub | ![Night](screenshots/phone/desk-99-night.png) Night |

**Laptop side:** the bridge console (real output; two lines that only appear on Linux were
removed) and the optional web dashboard in Chromium.

![Bridge console](screenshots/desktop/01-bridge-console.png)
![Web pairing](screenshots/desktop/02-web-pairing.png)
![Web dashboard on a laptop](screenshots/desktop/03-web-dashboard-laptop.png)
![Web dashboard in a phone browser](screenshots/desktop/04-web-dashboard-phone-browser.png)
