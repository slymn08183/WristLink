# WristLink for iPhone

The iPhone half of WristLink. The watch app works without it (notifications, music controls, calls,
camera shutter all go through iOS's built-in Bluetooth services); this app adds what iOS doesn't
share with accessories on its own, such as Apple Health sync.

It is built on GitHub's Macs (see `.github/workflows/ios.yml`), so you don't need a Mac or a paid
Apple developer account. The build is unsigned; AltStore signs it with your own Apple ID when it
installs it.

## Install (Windows, no Mac)

Three ways, from most to least automatic. With a free Apple ID every sideloaded app expires
after 7 days and must be refreshed; only a paid developer account ($99/year) makes that a year.

### SideStore (recommended): refreshes and updates on the iPhone itself

After a one-time setup with a computer, SideStore re-signs apps on the iPhone, so the 7-day
refresh needs no computer, and WristLink updates install from the iPhone.

1. On the PC install iTunes (from apple.com) and [iloader](https://github.com/nab138/iloader)
   (only from its GitHub page or iloader.app).
2. On the iPhone install **LocalDevVPN** from the App Store, and turn on
   **Settings → Privacy & Security → Developer Mode**.
3. Connect the iPhone by cable, open iloader, sign in with your Apple ID and choose
   **Install SideStore**. It also places the pairing file SideStore needs.
4. On the iPhone: **Settings → General → VPN & Device Management** → trust your Apple ID.
5. Open LocalDevVPN → **Connect**, then SideStore → **Sources → +** and add
   `https://github.com/slymn08183/WristLink/releases/download/ios-latest/source.json`.
   Install WristLink from there.
6. Every few days (before 7 are up): LocalDevVPN → Connect, SideStore → **Refresh All**. New
   WristLink builds show up in SideStore as updates.

If you update or reset the iPhone, the pairing file may stop working; run iloader again.

### Sideloadly: simplest, but needs the computer every 7 days

Install [Sideloadly](https://sideloadly.io), connect the iPhone, drop in `WristLink.ipa` from the
[latest build](https://github.com/slymn08183/WristLink/releases/tag/ios-latest), sign in, Start.

### AltStore Classic

One-time setup, about 15 minutes:

1. On the PC, install **iTunes** and **iCloud** from Apple's website (not the Microsoft Store
   versions; AltServer needs the Apple ones).
2. Install **AltServer** from [altstore.io](https://altstore.io) and start it (it sits in the tray).
3. Connect the iPhone with a cable, unlock it and tap **Trust**. In iTunes, turn on
   **Sync with this iPhone over Wi-Fi** so later refreshes work without the cable.
4. From the AltServer tray icon: **Install AltStore → your iPhone**, and sign in with your Apple ID.
5. On the iPhone: **Settings → General → VPN & Device Management** → trust your Apple ID.
   On iOS 16 and later also turn on **Settings → Privacy & Security → Developer Mode** (the phone
   restarts).

Install WristLink:

1. On the iPhone, open the
   [latest iPhone build](https://github.com/slymn08183/WristLink/releases/tag/ios-latest) in Safari
   and download `WristLink.ipa`.
2. Open **AltStore → My Apps → +** and pick `WristLink.ipa` from Downloads.
3. Open WristLink, allow Bluetooth, pick your watch from the list.

Apps signed with a free Apple ID expire after 7 days. AltStore refreshes them in the background
whenever the iPhone and the PC running AltServer are on the same Wi-Fi; you can also refresh by
hand in AltStore.

## How it talks to the watch

The iPhone is already connected to the watch for notifications. The app asks iOS for the devices it
is connected to and uses the watch's **WristLink Companion** GATT service
(`57a1e1c0-0001-4b8e-9a1f-6f4b10000000`) over that same connection, so the watch never has to
advertise and the app can run in the background (`bluetooth-central`).

## Building

The Xcode project is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
cd ios
xcodegen generate
open WristLink.xcodeproj
```
