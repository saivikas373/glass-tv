# [📄 Read the setup guide first](docs/Glass-TV-Setup-Guide.pdf)

# Glass TV

A calm, ad-free home screen for **Android TV and Google TV** (Android 9 and newer), with a phone remote that only answers devices on your own Wi-Fi.

![Glass home](docs/screenshots/home.png)

| Control Center | Ambient mode |
|---|---|
| ![Control Center](docs/screenshots/control-center.png) | ![Ambient](docs/screenshots/ambient.png) |

- Big glass app tiles with each app's real banner; the apps you use most move to the front
- Control Center from the remote's settings button: Wi-Fi, Bluetooth, Now Playing, today's calendar, inputs, speed test, 12 themes
- Ambient mode: a big clock, photos sent from your phone, sticky notes, your next event
- Phone remote in the browser: arrows, typing, voice search, messages, photos, links, apps and inputs
- No account, no cloud, no ads, no tracking. Nothing is ever uninstalled from your TV

## Install

Downloads are on the [latest release](https://github.com/saivikas373/glass-tv/releases/latest).

### Easy path (TV only)
1. On the TV, install **Downloader** from the Play Store.
2. Open Downloader and enter `tinyurl.com/glasstv-version`.
3. Open Glass and follow its setup screen: **Turn on Glass**, **Show what's playing**, **Pick a look**, **Done: press Home**.

If step 1 of the setup says *"Your TV blocked this"* (common on Android 13+ for apps installed with Downloader), use the Full path.

### Full path (computer, also switches off ad and tracking apps)
1. On the TV: Settings → System → About → press OK on **Android TV OS build** 7 times, then Settings → System → Developer options → **USB debugging** on.
2. Download `Glass-TV-Setup-Mac.zip` or `Glass-TV-Setup-Windows.zip` and unzip it.
3. Double-click **Glass-Setup** (Mac: the first time, right-click → Open → Open. Windows: if SmartScreen appears, More info → Run anyway).
4. Type your TV's IP address when it asks, and press **Allow** on the TV.

It downloads Google's official ADB tool, backs up the TV, switches off a conservative list of ad and tracking apps (only if installed, never uninstalled), installs and turns on Glass, then **closes ADB** and deletes its key.
**Keep the folder: Glass-Restore undoes everything.**

### After a TV update
If Glass shows **"TV updated: run repair"**: Easy installs turn Glass off and on in Settings → Accessibility; Full installs turn USB debugging on and double-click Glass-Setup again.

## Security
The phone remote listens on the TV's Wi-Fi address only, refuses anything outside your home subnet, pairs each phone once with a 6-digit PIN shown on the TV (5 wrong PINs lock pairing for 5 minutes), and refuses DNS-rebinding and cross-site requests. App installs from a link need https and your confirmation on the TV. See `setup/security-test.sh`.

## Build
Needs JDK 17 and the Android SDK (platform 34).

```bash
cd glass
./gradlew assembleRelease
```

That gives an unsigned APK in `glass/app/build/outputs/apk/release/`. Sign it with your own key before installing it. A build signed with your key can't install over the official Glass until the official one is removed. `glass/build-release.sh` is the maintainer's script and needs the maintainer's key. `python3 setup/make-release.py` builds the setup zips from `setup/src`.

## Licenses
Glass TV is MIT licensed (`LICENSE`). The safe Home-switch approach and the ad/tracking package lists come from [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT, Copyright (c) 2026 Gathin). The Inter typeface is under the SIL Open Font License 1.1 (`FONT-LICENSE-OFL-Inter.txt`).

Not affiliated with Google, Apple or any TV maker.

## Two things that surprise people

- **Finding Glass:** in Accessibility, Glass is at the very bottom of the Services list. Keep pressing Down.
- **The scary warning:** Android shows "Glass can collect all of the text you type" for every accessibility app. Glass uses this only to catch the Home and settings buttons and to type what you send from your phone. It has no account and sends nothing to the internet except the weather you ask for.

## TCL TVs: use the computer setup if you can

TCL blocks apps from starting by themselves after a full restart, and only the computer setup can allow it. With the TV-only setup on a TCL, Glass still works, but after a restart you get the normal Google TV home until you fix it:

1. Open Glass from your apps. The phone remote comes back.
2. Go to Settings → Accessibility → Glass, turn it off, then on again. The Home button comes back.

Normal standby doesn't cause this, only a full restart (for example a power cut or a TV update). Other brands may do the same; this has only been tested on one TCL TV.
