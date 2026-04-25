# AutoDial

Android app that automates repeated VoIP calls through **BizPhone** (`com.b3networks.bizphone`) or **Mobile VOIP** (`finarea.MobileVoip`) for a small sales-team fleet. Enter a number, a hang-up timer, and a cycle count, hit START — AutoDial drives the target app's UI via an Accessibility Service, placing the call, waiting, hanging up, and looping, while a floating overlay bubble shows progress.

> **Alpha, sideload-only.** Not on the Play Store. Built for an internal fleet, not general distribution.

## How it works

AutoDial doesn't dial through any phone API — it watches and taps the target app's UI like a user would. A one-time **per-app recording** (the "recipe") captures where the dial pad, digit keys, call button, and hang-up button live. At run time the **AutoDialAccessibilityService** replays those taps in a loop, with a 3-tier fallback (resource-id → text+bounds → raw coordinate gesture) so small UI shifts don't break the run. A foreground service holds the wake lock and a state machine drives the cycle: `LaunchingTarget → OpeningDialPad → EnteringNumber → PressingCall → InCall → HangingUp → next cycle`.

The run stays inside the target app the whole time — AutoDial's UI is the floating overlay, not a separate screen.

## Status

Active prototype. Currently at `0.2.6-alpha`. The dialing approach is still being iterated on (BizPhone has a non-standard custom dial pad that's been the source of most fragility). See [`docs/superpowers/specs/2026-04-24-autodial-prd.md`](docs/superpowers/specs/2026-04-24-autodial-prd.md) for the full product design and rationale.

## For testers

Install + first-run setup + usage + known limitations are all in **[TESTERS.md](TESTERS.md)**. Short version:

1. Sideload the APK.
2. Walk the 8-step onboarding (Accessibility, Overlay, Notifications, Battery, OEM tweaks, target install, recipe recording).
3. Open the target app's dial pad first, switch to AutoDial, set number / cycles / hang-up timer, hit START.

## For contributors

Build from **Android Studio** — there's no `gradlew` wrapper committed and no command-line build path. Unit tests in `app/src/test/`, instrumentation tests in `app/src/androidTest/`.

[`CLAUDE.md`](CLAUDE.md) is the architectural overview — read it before changing run behavior. The four cooperating components are:

| Component | Path | Role |
|---|---|---|
| UI (Compose) | `app/src/main/java/com/autodial/ui/` | Dialer, Active Run, Settings, History, Onboarding, overlay Setup wizard |
| `AutoDialAccessibilityService` | `app/src/main/java/com/autodial/accessibility/` | Reads + taps target-app nodes; wraps `UiRecorder` and `UiPlayer` |
| `RunForegroundService` | `app/src/main/java/com/autodial/service/` | Owns the wake lock + foreground notification + state machine |
| `OverlayController` | `app/src/main/java/com/autodial/overlay/` | Floating `TYPE_APPLICATION_OVERLAY` bubble showing live run status |

After any change to the recipe-step schema, `adb uninstall com.autodial` before retesting — old rows will silently break runs with `failed:step-not-recorded`.

## Privacy

Phone numbers and run history are stored locally only (Room DB). No network calls, no analytics, no telemetry. Uninstall wipes everything.

## License

No license declared yet — all rights reserved by default.
