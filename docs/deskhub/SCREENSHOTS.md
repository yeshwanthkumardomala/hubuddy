# Screenshots

Rendered from the real app code with Robolectric (Android 14 runtime) and sample data from fake PCs; they
are not photos of a physical phone. Portrait is 720 × 1520 px (Vivo 1902 size); landscape is 1544 × 720 px.
Regenerate with: `gradlew testDebugUnitTest --tests "*Screenshots*" -Pscreenshots=<folder>`

## Landscape: side rail and columns

| | |
| --- | --- |
| ![Home](screenshots/phone/30-landscape-home-dark.png) Home (dark) | ![All PCs](screenshots/phone/31-landscape-all-pcs-dark.png) All PCs |
| ![One PC](screenshots/phone/32-landscape-monitor-one-pc-dark.png) One PC | ![AI](screenshots/phone/33-landscape-ai-dark.png) Claude Code |
| ![Controls](screenshots/phone/34-landscape-controls-dark.png) Controls | ![Settings](screenshots/phone/35-landscape-settings-dark.png) Settings |
| ![Home light](screenshots/phone/40-landscape-home-light.png) Home (light) | ![All PCs light](screenshots/phone/41-landscape-all-pcs-light.png) All PCs (light) |

## Portrait

| | | |
| --- | --- | --- |
| ![Home](screenshots/phone/03-home.png) Home | ![Home with 4 PCs](screenshots/phone/20-home-4pcs-dark.png) Home with several PCs | ![All PCs](screenshots/phone/21-all-pcs-dark.png) All PCs |
| ![Home light](screenshots/phone/22-home-light.png) Light theme | ![All PCs light](screenshots/phone/23-all-pcs-light.png) All PCs (light) | ![AI light](screenshots/phone/24-ai-light.png) Claude Code (light) |
| ![Monitor](screenshots/phone/04-monitor.png) One PC | ![Network](screenshots/phone/05-network.png) Network | ![GitHub](screenshots/phone/06-github.png) GitHub |
| ![AI](screenshots/phone/07-ai-claude-code.png) Claude Code | ![Controls](screenshots/phone/08-controls.png) Controls | ![Calendar](screenshots/phone/09-calendar.png) Calendar |
| ![Weather](screenshots/phone/10-weather.png) Weather | ![Settings](screenshots/phone/12-settings.png) PCs and settings | ![Settings light](screenshots/phone/26-settings-light.png) Settings (light) |
| ![Login](screenshots/phone/01-login.png) Login | ![PIN](screenshots/phone/02-set-new-pin.png) First-login PIN change | ![Offline](screenshots/phone/11-home-offline-not-paired.png) Not paired / offline |

## Desk mode (idle display)

Starts after 5 minutes without a touch (also on battery, stopping at 15%), rotates through every page that
has data, and dims to a clock at night. Touch to leave.

| | | |
| --- | --- | --- |
| ![Clock](screenshots/phone/desk-07-clock.png) Clock | ![All PCs](screenshots/phone/desk-fleet.png) All PCs | ![PC](screenshots/phone/desk-08-pc.png) PC |
| ![Claude](screenshots/phone/desk-01-claude.png) Claude Code | ![Media](screenshots/phone/desk-02-media.png) Media | ![Calendar](screenshots/phone/desk-03-calendar.png) Calendar |
| ![Weather](screenshots/phone/desk-04-weather.png) Weather | ![Network](screenshots/phone/desk-05-network.png) Network | ![GitHub](screenshots/phone/desk-06-github.png) GitHub |
| ![Night](screenshots/phone/desk-99-night.png) Night | | |
