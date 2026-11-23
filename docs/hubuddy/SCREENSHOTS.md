# Screenshots

Phone screens are rendered from the real app code with Robolectric (Android 14 runtime) using sample data from
fake PCs; they are not photos of a physical phone. Portrait is 720 × 1520 px, landscape 1544 × 720 px.
Regenerate: `gradlew testDebugUnitTest --tests "*Screenshots*" -Pscreenshots=<folder>`.

## Landscape

| | |
| --- | --- |
| ![Home](screenshots/phone/30-landscape-home-dark.png) Home | ![All PCs](screenshots/phone/31-landscape-all-pcs-dark.png) All PCs |
| ![One PC](screenshots/phone/32-landscape-monitor-one-pc-dark.png) One PC | ![AI](screenshots/phone/33-landscape-ai-dark.png) Claude Code |
| ![Controls](screenshots/phone/34-landscape-controls-dark.png) Controls | ![Settings](screenshots/phone/35-landscape-settings-dark.png) Settings |
| ![Home light](screenshots/phone/40-landscape-home-light.png) Home (light) | ![All PCs light](screenshots/phone/41-landscape-all-pcs-light.png) All PCs (light) |

## Portrait

| | | |
| --- | --- | --- |
| ![Home](screenshots/phone/03-home.png) Home | ![Several PCs](screenshots/phone/20-home-4pcs-dark.png) Home, several PCs | ![All PCs](screenshots/phone/21-all-pcs-dark.png) All PCs |
| ![Light](screenshots/phone/22-home-light.png) Light theme | ![All PCs light](screenshots/phone/23-all-pcs-light.png) All PCs (light) | ![AI light](screenshots/phone/24-ai-light.png) Claude Code (light) |
| ![Monitor](screenshots/phone/04-monitor.png) One PC | ![Network](screenshots/phone/05-network.png) Network | ![GitHub](screenshots/phone/06-github.png) GitHub |
| ![AI](screenshots/phone/07-ai-claude-code.png) Claude Code | ![Controls](screenshots/phone/08-controls.png) Controls | ![Calendar](screenshots/phone/09-calendar.png) Calendar |
| ![Weather](screenshots/phone/10-weather.png) Weather | ![Settings](screenshots/phone/12-settings.png) PCs and settings | ![Settings light](screenshots/phone/26-settings-light.png) Settings (light) |
| ![Login](screenshots/phone/01-login.png) Login | ![PIN](screenshots/phone/02-set-new-pin.png) First-login PIN change | ![Offline](screenshots/phone/11-home-offline-not-paired.png) Not paired |

## Dock mode (idle display)

| | | |
| --- | --- | --- |
| ![Clock](screenshots/phone/dock-07-clock.png) Clock | ![All PCs](screenshots/phone/dock-fleet.png) All PCs | ![PC](screenshots/phone/dock-08-pc.png) PC |
| ![Claude](screenshots/phone/dock-01-claude.png) Claude Code | ![Media](screenshots/phone/dock-02-media.png) Media | ![Calendar](screenshots/phone/dock-03-calendar.png) Calendar |
| ![Weather](screenshots/phone/dock-04-weather.png) Weather | ![Network](screenshots/phone/dock-05-network.png) Network | ![GitHub](screenshots/phone/dock-06-github.png) GitHub |
| ![Night](screenshots/phone/dock-99-night.png) Night | | |

## PC side

The page the PC program opens at `http://127.0.0.1:8766/`, running for real with two sample phones paired (this is the page behind “Pair a phone”). The QR code is a one-time example.

![Pair a phone with a QR code](screenshots/pc/01-pair-qr.png)
![Pair with a PIN and compare code](screenshots/pc/02-pair-pin.png)
