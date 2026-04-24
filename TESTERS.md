# AutoDial — Tester Guide (alpha 0.2.0)

Thanks for helping test AutoDial. This is a **sideloaded, alpha-quality** Android app that automates repeated VoIP calls through BizPhone or Mobile VOIP. It is not on the Play Store.

## What it does

Enter a phone number, a hang-up timer, and a cycle count. Tap START. AutoDial opens the target app (BizPhone or Mobile VOIP), dials the number, waits your timer, hangs up, and repeats.

## Install

1. On your phone: **Settings → Apps → Special access → Install unknown apps** — enable it for your file manager or browser.
2. Download the APK you were sent.
3. Tap the APK in the file manager → **Install**.
4. When prompted about updates in future, re-enable the unknown-sources toggle if the OS cleared it.

## First-run setup

Open AutoDial. You'll go through an 8-step setup:

1. **Welcome** — tap Get Started.
2. **Accessibility service** — opens system Settings; find "AutoDial" in the list and toggle it on. Come back.
3. **Overlay permission** — system Settings; toggle "Display over other apps" for AutoDial. Come back.
4. **Notifications** — tap Grant; accept the prompt.
5. **Battery optimization** — exempt AutoDial so the OS doesn't kill it mid-run.
6. **OEM tweaks** — if you're on Oppo / OnePlus / Samsung / Xiaomi / Huawei, follow the listed steps. If the list is empty, your phone is already permissive.
7. **Install target apps** — at least one of BizPhone / Mobile VOIP installed.
8. **Record recipe** — BizPhone and Mobile VOIP each need a one-time recipe recording. An overlay card will guide you through 4 taps inside each target app. Takes about 30 seconds per app.

## Using AutoDial

1. Enter a phone number.
2. Set cycles (how many times to dial).
3. Set hang-up after (how long each call lasts in seconds).
4. Pick the target (BizPhone or Mobile VOIP — tap "(SETUP)" if not recorded).
5. Optional: toggle Spam mode (runs until stopped or hits the safety cap).
6. Tap START. AutoDial switches to the target app and starts dialing.
7. The overlay bubble shows the current cycle and countdown. Tap STOP on it or on the notification to end early.

## Known limitations

- **Phone calls during a run.** If an actual incoming call arrives while AutoDial is running, the run may stall or fail. Safest to enable Do Not Disturb while testing.
- **Screen off.** AutoDial tries to hold a wake lock, but some phones still dim/lock the screen aggressively. Keep the screen on for reliability.
- **Target-app updates.** If BizPhone or Mobile VOIP updates, the recipe may become stale (a yellow banner on Dialer flags this). Re-record from Settings → Recipes.
- **Phone rotation.** Recipes are recorded in portrait; running in landscape is untested.
- **The overlay Setup wizard** replaces the older in-app wizard. If you see any reference to a full-screen "Wizard" screen in old screenshots, that's been removed.

## Reporting bugs

- **Describe what you did** (number, cycles, target app), **what happened**, and **what you expected**.
- **Grab the crash log** if the app crashed:
  - File manager: Internal Storage → Android → data → `com.autodial` → files → `crash-log.txt`.
  - Or via USB: `adb pull /sdcard/Android/data/com.autodial/files/crash-log.txt`.
  - Attach that file to your bug report.
- **Logcat** (optional, deeper debugging): `adb logcat -s AutoDial:* > autodial.log` during reproduction.
- **Screenshot** the state the app is in if the UI looks wrong.

## Privacy / data

- Your phone numbers and run history are stored **locally only** on your device (Room database).
- No network calls, no analytics, no telemetry. Everything is on-device.
- If you uninstall, everything is deleted with the app.

## Version

`app/build.gradle.kts` — `versionName = "0.2.0-alpha"`, `versionCode = 2`.

Check the installed version in Settings → About.
