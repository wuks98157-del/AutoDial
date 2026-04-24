# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What AutoDial is

Android app (sideloaded only, not on Play Store) that automates repeated VoIP calls through **BizPhone** (`com.b3networks.bizphone`) or **Mobile VOIP** (`finarea.MobileVoip`) for a small sales-team fleet. The user enters a number, a hang-up timer, a cycle count (or spam mode), hits START, and AutoDial drives the target app's UI via an Accessibility Service — placing the call, waiting, hanging up, and looping — while an overlay bubble shows progress on top of the target app.

**Full product context lives in [docs/superpowers/specs/2026-04-24-autodial-prd.md](docs/superpowers/specs/2026-04-24-autodial-prd.md).** Read it before making any architectural decisions — it explains the "why" (e.g. why the run stays inside the target app the entire time, which is the single most important design decision).

## Build / run

No `gradlew` wrapper is committed (see `.gitignore` / project state). The project is built from **Android Studio** — there is no command-line build path in this repo. When asked to "build" something, either:
- Point the user at Android Studio's Build → Rebuild Project, or
- Remind them to reinstall cleanly with `adb uninstall com.autodial` first if recipe schema or step-ID data model has changed (old rows will cause `failed:step-not-recorded`).

Unit tests live in `app/src/test/` (JVM) and instrumentation tests in `app/src/androidTest/`. Run them from Android Studio.

## Architecture — the four components that cooperate

| Component | File | Responsibility |
|---|---|---|
| **UI layer** (Compose) | `ui/**` | Dialer, Active Run, Settings, History, Onboarding, and the per-app setup Wizard. One Activity, Compose-nav between screens. |
| **`AutoDialAccessibilityService`** | `accessibility/AutoDialAccessibilityService.kt` | Only component that can see/tap BizPhone's UI. Wraps `UiRecorder` (records node taps during wizard) and `UiPlayer` (replays actions during a run). Exposed as an in-process singleton via `instance`. |
| **`RunForegroundService`** | `service/RunForegroundService.kt` | Owns the run's wake lock + foreground notification + state machine. Drives the accessibility service. Runs for the entire duration of a run. |
| **`OverlayController`** | `overlay/OverlayController.kt` | Floating `TYPE_APPLICATION_OVERLAY` bubble rendered via `WindowManager.addView(ComposeView)`. Displays live run status on top of the target app. |

### The state machine
`RunStateMachine` (`service/`) drives cycles through: `Preparing → LaunchingTarget → OpeningDialPad → EnteringNumber → PressingCall → InCall → HangingUp → {next cycle | Completed}`. It's pure — events go in via `onEvent` / `onCommand`, state comes out of a `StateFlow`. `RunForegroundService.driveState` translates each state into accessibility actions and feeds `StepActionSucceeded` / `StepActionFailed` events back into the machine. **This loop is the heart of the app; understand it end-to-end before changing run behavior.**

### Per-target behaviors
`model/TargetApp.kt` holds per-app behavioral flags. Today only `retainsNumberAfterHangup(pkg)`:
- **BizPhone** retains the dialed number after hang-up → next cycle skips `EnteringNumber` and goes straight to `PressingCall`.
- **Mobile VOIP** clears → each cycle re-enters the number.

Add more per-target differences here rather than scattering `when (pkg)` branches across the codebase.

### Recipe model
Each target app has one `Recipe` row + N `RecipeStep` rows in Room (`data/db/`). The current step set is `OPEN_DIAL_PAD`, `DIGIT_0`..`DIGIT_9`, `CLEAR_DIGITS`, `PRESS_CALL`, `HANG_UP` (14 rows). `HANG_UP_RINGING` / `HANG_UP_CONNECTED` (in the PRD) and `RETURN_TO_DIAL_PAD` were collapsed / dropped — **confirm by reading `WizardViewModel.WIZARD_STEPS` and `RecipeStep.kt`'s comment rather than trusting memory or the PRD (which has an older list).** Each `RecipeStep` stores resourceId, text, className, normalized relative bounds, and a screenshot perceptual hash for the tapped node.

### UiPlayer tier strategy
`UiPlayer.executeStep` has a 3-tier fallback for finding the right node at playback time: (1) `findAccessibilityNodeInfosByViewId`, (2) text + class + bounds overlap, (3) raw coordinate gesture. After each, `verify()` computes a perceptual hash of the post-tap region and compares to the recorded one — **but a mismatch is non-fatal** (returns `ok:<tier>:hash-mismatch`). Hash verification is too strict to be a gate on transition-causing clicks; it stays for diagnostic logging only.

The `EnteringNumber` state's driver in `RunForegroundService.setNumber` uses this tier flow uniformly: it first taps `CLEAR_DIGITS` ~20 times to wipe any leftover input, then executes one recipe step per digit in the target number (`DIGIT_6`, `DIGIT_7`, …). The state machine emits a single virtual `NUMBER_FIELD` success event at the end, which advances to `PressingCall` — so the state machine stays simple (one state per phase) while the driver handles the per-digit iteration.

### UiRecorder sequence mode
`UiRecorder` supports single-step and multi-step sequence recording. A queue of pending step IDs gets consumed one-per-click. Used for things like recording a sequence of digit taps in one wizard step. Important subtlety: read `event.source` **before** popping the queue — otherwise a transition-firing click (view already detached) consumes a slot without producing a capture and the user gets stuck.

## Conventions that matter

- **OEM deep links** live in `oem/OemCompatibilityHelper.kt`, keyed off `Build.MANUFACTURER`. When adding OEM-specific workarounds, add them here and wire the UI through `OemSetupStep`.
- **Per-target package string literals** are scattered today but `model/TargetApp.kt` is where new target-app constants should go. Prefer `TargetApps.BIZPHONE` / `TargetApps.MOBILE_VOIP` over raw strings in new code.
- **Edge-to-edge + dark theme.** `MainActivity` calls `enableEdgeToEdge()`, theme is always dark (`AutoDialTheme`). Any screen whose root is a plain `Column` instead of `Scaffold`/`Surface` will render black-on-black because `LocalContentColor` never gets set — always wrap in `Surface(color = colorScheme.background)` or use a `Scaffold`.
- **Lifecycle-aware permission refresh.** When a screen needs to re-check something on returning from system settings, use `DisposableEffect` + `LifecycleEventObserver` listening for `ON_RESUME` — `LaunchedEffect(state.currentStep)` only fires on step change, which is a common trap (see `OnboardingScreen`).
- **Destructive-seeming state transitions on the phone.** Changing the recipe-step schema means old installs' rows won't match — always tell the user to `adb uninstall com.autodial` before retesting, or their prior recipe will silently break the run with `failed:step-not-recorded`.

## Where this app is in its lifecycle

AutoDial is an active prototype under iteration — the PRD is the intended design but the shipping code has diverged as accessibility-based dialing has proven fragile on BizPhone specifically (custom non-standard dial pad). Recent sessions have experimented with `ACTION_SET_TEXT`, per-digit recording, and runtime text-based digit lookup. If you're about to change the dialing approach, check [docs/superpowers/plans/](docs/superpowers/plans/) and the latest `git log` first — the "right" approach today may already have been tried.
