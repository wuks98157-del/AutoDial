# AutoDial — Product Requirements Document

**Version:** 1.0
**Date:** 2026-04-24
**Status:** Draft — awaiting user review
**Audience:** The Android developer implementing AutoDial with Claude Code. Assume the reader is a skilled developer but not yet deeply familiar with this specific app's history, constraints, or target fleet.

---

## 1. Overview

AutoDial is an internal Android tool used by the sales team to place repeated VoIP calls through **BizPhone** (`com.b3networks.bizphone`) or **Mobile VOIP** (`finarea.MobileVoip`). For each run the user specifies a phone number, a hang-up timer (how long each call lasts before AutoDial cancels it), and either a fixed cycle count or "spam mode" (repeat until stopped). AutoDial drives the target app's UI via an Accessibility Service, remaining inside the target app for the duration of the run and showing progress via a floating overlay.

The app is **sideloaded only** and distributed manually. It is not published to the Play Store. The existing fleet is 7 phones: 2 on Android 11, 5 on Android 12, from a mix of Xiaomi, Oppo, and Vivo.

The intent of each call is to register a missed call at the receiving end — the call is hung up after the configured timer regardless of whether it connected.

---

## 2. Goals and Non-Goals

### Goals
- Reliably drive BizPhone and Mobile VOIP to place and hang up calls on each phone in the fleet.
- Remain functional across Xiaomi, Oppo, and Vivo OEM skins, which aggressively kill background services and revoke accessibility permissions.
- Provide a teach-by-demonstration setup wizard so the app adapts to each target app's UI without hard-coded coordinates.
- Survive minor UI changes in the target apps through a hybrid (node + coordinate + screenshot-hash) UI-element fingerprinting strategy.
- Be operable by a non-technical sales user: one number, one tap, the run runs itself.

### Non-Goals (v1)
- WhatsApp support (removed from scope — mockups reference it, treat as a UI update).
- iOS.
- Multi-number queue (dial from a CSV list).
- Remote control, web dashboard, or telemetry.
- Call recording or transcription.
- Multiple concurrent runs on one device.
- Root, Shizuku, or device-admin variants.
- Play Store distribution (accessibility-service policy would block us anyway).

---

## 3. Users and Use Cases

**Primary user:** a sales operator. Picks up a fleet phone that is plugged into a charger, opens AutoDial, types a Singapore local phone number (e.g. `67773777`), sets cycle count or toggles spam mode, taps START, and walks away. The phone calls the number, hangs up after the timer, and repeats.

**Secondary user:** the provisioner (likely the same engineer reading this PRD). Sets up each new fleet phone once: installs apps, grants permissions, walks through the per-app recording wizards, and hands the phone to the sales operator.

### Representative flows

1. **Happy path run.** Operator types `67773777`, sets cycles = 10, hang-up = 25s, target = BizPhone, taps START. AutoDial launches BizPhone, inputs the number on BizPhone's dial pad, taps BizPhone's call button, waits 25s, taps BizPhone's hang-up button, returns to BizPhone's dial pad, and repeats 10 times. Overlay shows progress. Run ends, history records a row.
2. **Spam-mode run.** Same flow but cycles is "∞". Runs until operator taps STOP on overlay / notification / Active Run screen, or safety cap (default 9,999) is reached.
3. **UI drift.** BizPhone updates and renames the dial button. Operator's next run fails at `PRESS_CALL` step. History explains why. Operator goes to Settings → Re-run setup for BizPhone → walks through wizard again. Next run works.
4. **OEM revokes accessibility.** Operator opens AutoDial after a week. Banner says "Accessibility service was disabled — tap to re-enable." Operator taps, flips the toggle in system settings, returns, resumes work.

---

## 4. Fleet and Target Platforms

**Active fleet (2026-04-24):** 7 phones — 2 on Android 11 (API 30), 5 on Android 12 (API 31). Mix of Xiaomi, Oppo, Vivo. The everyday reliability battle is Android 12 BAL rules + those three OEMs' battery managers — **test against Android 12 Xiaomi/Oppo/Vivo first; Android 13/14/15 support is forward-compat insurance.**

**Target SDK policy:**
- `minSdk = 30` (Android 11)
- `targetSdk = 35` (Android 15)
- `compileSdk = 35`

Targeting 35 is required for forward compatibility and opts us into every Android behavior change from 11 through 15 simultaneously. We cannot dodge Android 12's BAL rules by targeting a lower SDK — Android 12+ devices apply those rules regardless of `targetSdk` in most cases.

**Build tooling:** Kotlin 2.x, Jetpack Compose, Gradle 8.x, Android Gradle Plugin 8.x, Hilt for DI, Room for local DB, DataStore Proto for settings, Kotlin Coroutines + Flow for async.

---

## 5. Architecture Overview

AutoDial is a single Android app consisting of four cooperating components:

| Component | Responsibility |
|---|---|
| **UI Layer** (Activities + Compose) | 4 main screens + onboarding + per-app setup wizard |
| **`AutoDialAccessibilityService`** | The only component that can see and tap BizPhone / Mobile VOIP's UI. Exposes an in-process API (bound service) that the foreground service calls. |
| **`RunForegroundService`** | Holds the wake lock, owns the run state machine, publishes state to UI and overlay. Persistent notification with STOP action. |
| **`OverlayController`** | Floating bubble via `WindowManager` (requires `SYSTEM_ALERT_WINDOW`). Shows live run status on top of the target app. |

### Data flow on START

1. `DialerScreen` validates input, confirms recipe exists for selected target, and calls `RunForegroundService.start(params)`.
2. Service acquires wake lock, calls `AccessibilityService.beginRun(params)` via the bound in-process API.
3. AccessibilityService launches the target app via `startActivity(packageManager.getLaunchIntentForPackage(pkg).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK))`. Allowed because AutoDial is currently foreground at the moment of START — no BAL restriction applies.
4. AccessibilityService runs the state machine (Section 7), staying inside the target app between cycles. Never returns to AutoDial until the run ends.
5. OverlayController renders the live bubble on top of the target app throughout the run.
6. On completion / STOP / failure, the service logs a `RunRecord`, ends the foreground service, releases the wake lock, dismisses the overlay, and brings AutoDial to the front.

### Why stay inside the target app the entire run

Returning to AutoDial between cycles requires re-launching the target app from the background, which triggers Android 12's BAL restrictions **and** every OEM battery manager's background-start block. This is the single most likely cause of the previous app's "start button does nothing" failure. By never leaving the target app during a run, we sidestep this entire class of failure.

The only moment we touch `startActivity` for the target app is on the initial START press, when AutoDial is guaranteed foreground.

---

## 6. UI Automation Engine

This section describes how AutoDial records and replays UI interactions with target apps. Three components: **Recipe**, **Wizard**, **Player**.

### 6.1 Recipe

A Recipe is a JSON document stored in Room, one per target app. It captures how to perform each of the standard steps needed to place and cancel a call.

**Required steps (per app):**

| Step ID | Meaning |
|---|---|
| `OPEN_DIAL_PAD` | Tap the keypad icon inside the target app to reach the numeric dialer |
| `DIGIT_0` … `DIGIT_9` | The ten digit buttons on that dial pad |
| `PRESS_CALL` | The green call button |
| `HANG_UP_CONNECTED` | End-call button during a connected call |
| `HANG_UP_RINGING` | Cancel button while the call is still ringing out |
| `RETURN_TO_DIAL_PAD` | How to return to the keypad after a hangup (usually back gesture, sometimes a "Keypad" tab) |

**What each step stores (`UiTarget`):**

```
{
  "resourceId":  "com.b3networks.bizphone:id/btnDigit5",       // primary fingerprint, nullable
  "text":        "5",                                           // secondary fingerprint, nullable, locale-aware
  "className":   "android.widget.Button",                       // tertiary filter
  "boundsRelX":  0.50, "boundsRelY": 0.62,                      // normalized 0-1 screen coords
  "boundsRelW":  0.33, "boundsRelH": 0.11,
  "screenshotHashHex": "ab39c2d4e5f61783",                      // 64-bit perceptual hash of the captured button region
  "recordedOnDensityDpi": 420,
  "recordedOnScreenW": 1080,
  "recordedOnScreenH": 2340
}
```

**Launching the app itself is not a recipe step.** It uses the launcher intent obtained via `PackageManager.getLaunchIntentForPackage(targetPackage)`. Recipes only capture in-app UI interactions.

### 6.2 Wizard

A full-screen activity that walks the user through each step for the selected target app.

For each step:
1. AutoDial displays an instruction card: *"Open BizPhone and tap the '5' digit. I'll watch."* plus a "Start recording" button.
2. On tap, AutoDial launches the target app (or brings it forward if already open) and sets a flag in the AccessibilityService to enter **record mode**.
3. The AccessibilityService listens for the next `TYPE_VIEW_CLICKED` event originating from the target package, debouncing the first 500ms after launch to skip spurious events.
4. On the qualifying click, the service extracts from the source `AccessibilityNodeInfo`:
   - `getViewIdResourceName()` → `resourceId` (may be null)
   - `getText()` → `text` (may be null)
   - `getClassName()` → `className`
   - `getBoundsInScreen()` → normalized relative bounds
   - `takeScreenshot()` (API 30+) → cropped to the node bounds → perceptual hash (8×8 DCT-based pHash, 64 bits)
5. The service returns to AutoDial foreground and shows a confirm card: *"Captured digit '5'. Re-record or Next?"*
6. If `resourceId` is null, the confirm card shows a yellow warning: *"This target app did not expose a resource ID for this button. We'll rely on text + coordinates at runtime, which may be less stable."* This surfaces the strip-IDs risk at record time so the engineer can adapt.

Digits 0–9 are captured in a batch flow — the wizard shows a single instruction *"Tap each digit 0 through 9 in order"* and records them sequentially without returning to AutoDial between digits.

**Hangup recording** — the wizard explicitly prompts for both states:
- "Call any working number. When the phone starts ringing out (before the other end picks up), tap the cancel button." → captures `HANG_UP_RINGING`.
- "Call again and wait until the call connects (or until voicemail picks up). Tap the end-call button." → captures `HANG_UP_CONNECTED`.

If both states use the same button (same node, same screen location), the Player will detect this and treat them as one step internally.

### 6.3 Player

At runtime, for each step the Player resolves the stored `UiTarget` to an on-screen action with a four-tier resolution strategy:

**Tier 1 — resourceId match (primary).**
```kotlin
rootInActiveWindow.findAccessibilityNodeInfosByViewId(stored.resourceId)
  .firstOrNull { it.isVisibleToUser && it.className == stored.className }
  ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
```
If found and click returns true, step done.

**Tier 2 — text + class + bounds overlap (secondary).**
Traverse the active window's node tree, match where `node.text == stored.text` AND `node.className == stored.className` AND `node.boundsInScreen` overlaps `stored.relativeBounds × currentScreenSize` by >50%. Click the best match.

**Tier 3 — coordinate fallback.**
Dispatch a 60ms tap gesture via `dispatchGesture` at the center of `stored.relativeBounds × currentScreenSize`:
```kotlin
val cx = stored.boundsRelX * screenW + (stored.boundsRelW * screenW) / 2
val cy = stored.boundsRelY * screenH + (stored.boundsRelH * screenH) / 2
val path = Path().apply { moveTo(cx, cy) }
dispatchGesture(GestureDescription.Builder()
  .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
  .build(), null, null)
```

**Tier 4 — verification.**
300ms after the tap, re-capture the same region via `takeScreenshot()` and compute its perceptual hash. Compare to `stored.screenshotHashHex` using Hamming distance.
- Distance ≤ 10 bits AND the expected next-screen's node is reachable → step succeeded.
- Distance > 10 bits OR next-screen node missing after 3s → step failed. Record `failure: hash-mismatch` or `failure: timeout-next-state` in `RunStepEvent` and abort the run.

Verification runs on every step, regardless of which tier succeeded. The tier used is logged to `RunStepEvent.outcome` in the canonical form defined in §13.1 (`ok:node-primary` / `ok:node-fallback` / `ok:coord-fallback` / `failed:hash-mismatch` / `failed:timeout` / `failed:target-closed`) so post-mortem analysis can see which phones are falling back to which tiers.

### 6.4 Re-recording

Settings exposes three re-record actions:
- "Re-record BizPhone recipe"
- "Re-record Mobile VOIP recipe"
- "Re-run full first-time setup"

Each wipes the relevant data and launches the wizard. The recipe's `recordedAt` and `recordedVersion` are updated. A stale-recipe banner appears on the Dialer screen if the currently installed version of the target app differs from `recordedVersion`, prompting a re-record.

### 6.5 Test Recipe

Settings exposes a "Test recipe" button per target app. When tapped, a dialog prompts the provisioner for a test number (pre-filled with any number they've previously entered for testing, stored in memory only — not persisted). It runs a 1-cycle dry run with a short hangup (5s) and shows a per-step pass/fail result from the `RunStepEvent` log. The resulting `RunRecord` is tagged with `failureReason = "test-recipe"` in history so test runs can be distinguished from real ones. This is used after provisioning to verify every recorded step works before handing the phone to the operator.

---

## 7. Run State Machine

One instance lives in `RunForegroundService`. Driven by events published by `AutoDialAccessibilityService`.

### 7.1 States

```
IDLE
  → PREPARING              (validate recipe, acquire wake lock, start FGS)
  → LAUNCHING_TARGET       (startActivity of target app)
  → OPENING_DIAL_PAD       (execute OPEN_DIAL_PAD step)
  → TYPING_DIGITS          (execute DIGIT_x steps in sequence, with inter-digit delay)
  → PRESSING_CALL          (execute PRESS_CALL step)
  → IN_CALL                (countdown hang-up timer; do nothing)
  → HANGING_UP             (execute HANG_UP_CONNECTED or HANG_UP_RINGING, whichever button is visible)
  → RETURNING_TO_DIAL_PAD  (execute RETURN_TO_DIAL_PAD step)
  → [if more cycles]       → TYPING_DIGITS
  → [if no more cycles]    → COMPLETED

Any state → STOPPED_BY_USER (on STOP command; current call is hung up first)
Any state → FAILED          (on unrecoverable error; current call is hung up first)
```

### 7.2 Per-state timeouts

Timeouts prevent a stalled run from hanging forever. Configurable in Settings (advanced); defaults shown.

| Transition | Timeout |
|---|---|
| `LAUNCHING_TARGET` → `OPENING_DIAL_PAD` | 5000 ms |
| `OPENING_DIAL_PAD` → `TYPING_DIGITS` | 3000 ms |
| Inter-digit delay | 400 ms (default, user-configurable 100–2000) |
| `PRESSING_CALL` → `IN_CALL` | 3000 ms (detected by visibility of hangup button) |
| `IN_CALL` countdown | user-configured hang-up seconds |
| `HANGING_UP` → `RETURNING_TO_DIAL_PAD` | 3000 ms |
| Inter-cycle settle delay | 800 ms |

On timeout, the step is marked failed, a `RunStepEvent` is logged with reason, and the run transitions to `FAILED`.

### 7.3 Events (AccessibilityService → RunForegroundService)

Published via `SharedFlow<RunEvent>`:

- `TargetForegrounded(packageName)` — target app just became foreground
- `TargetBackgrounded(packageName)` — target app left foreground
- `StepActionSucceeded(stepId, tier)` — step executed, tier used
- `StepActionFailed(stepId, reason)` — step failed
- `NodeAppeared(anchorStepId)` — a known anchor node became visible (used to detect state transitions, e.g. hangup button appearing signals `IN_CALL`)

### 7.4 STOP

Three entry points, all send `RunCommand.Stop` to `RunForegroundService`:
1. STOP button on Active Run Screen
2. STOP button on overlay bubble
3. STOP action on persistent notification

On STOP:
1. If current state is `IN_CALL` or later: execute `HANG_UP_CONNECTED || HANG_UP_RINGING` (whichever button visible).
2. Transition to `STOPPED_BY_USER`.
3. Log `RunRecord` with `status = STOPPED` and `completedCycles` set to the last completed cycle.
4. Dismiss overlay, stop FGS, release wake lock, bring AutoDial to foreground.

### 7.5 Spam mode

`plannedCycles = 0` in `RunRecord` denotes spam mode. State machine treats the cycle counter as "increment forever" until:
- User hits STOP
- `completedCycles >= spamModeSafetyCap` (default 9,999, configurable in Settings)

### 7.6 Target app force-closed mid-run

If `TargetBackgrounded(pkg)` fires while state is not `LAUNCHING_TARGET` and the next-foreground activity is not the target app returning, the run transitions to `FAILED` with reason `target-app-closed`. This handles the case where the operator (or OEM) swipes the target app off Recents.

---

## 8. Screens

Layout and visual language follow `AutoDial.html` mockups. Dark-only theme (no light mode). Compose + Material 3.

> **UI update required:** the mockups reference "WhatsApp" as an alternate target. Replace with "Mobile VOIP" everywhere. WhatsApp is out of scope.

### 8.1 Dialer Home (mockup #1)

- **Number field** — numeric soft keyboard, max 15 digits, no formatting or auto-formatting, plain digits only. Singapore-style local numbers are the typical case (e.g. `67773777`), but the field accepts any length.
- **Cycles stepper** — integer 1 to `spamModeSafetyCap`. Hidden (replaced with "∞") when spam mode is on.
- **Hang-up duration stepper** — integer 1 to 600 seconds.
- **Target app toggle** — two pills, **BizPhone** and **Mobile VOIP**. Each pill is disabled if that target's recipe is not yet recorded; tapping a disabled pill links to the setup wizard for that app.
- **Spam mode switch** — when on, cycles stepper is replaced with "∞" and a subtle "max 9,999" hint appears.
- **START button** — large orange. Disabled when: number is empty, selected target's recipe is missing, or onboarding is incomplete.
- **Nav** — Settings icon (top-right), History icon.

Validation on START:
1. Number is non-empty and all digits.
2. Selected target's recipe exists and `schemaVersion` matches current app.
3. Accessibility service is currently enabled.
4. Overlay permission is granted.
5. No other run is currently active.

Failed validation: toast with the specific reason; deep-link to the fix where applicable.

### 8.2 Active Run (mockup #2)

Shown when AutoDial is foregrounded during a run. Pure status, no controls other than STOP.

- Current number (large, centered)
- Cycle counter (e.g. "3 / 10")
- Big seconds-until-hangup countdown ring
- Sub-label indicating current sub-state ("dialing", "in call", "hanging up", "returning to dial pad")
- "via BizPhone" / "via Mobile VOIP" indicator
- Large red STOP button
- Does **not** acquire `FLAG_KEEP_SCREEN_ON` itself (the wake lock in `RunForegroundService` handles screen-on — see §11).

If user backgrounds AutoDial (which is normal since the target app owns the foreground during runs), the overlay bubble takes over display.

### 8.3 Settings (mockup #3)

Defaults section:
- Default hang-up duration (integer seconds)
- Default number of cycles (integer)
- Default target app (BizPhone or Mobile VOIP)

Advanced section:
- Spam-mode safety cap (integer, default 9,999)
- Inter-digit tap delay in ms (integer, default 400)
- Overlay position reset button

Recipes section:
- For each target app: "Re-record BizPhone recipe" / "Re-record Mobile VOIP recipe", with last-recorded timestamp and `recordedVersion`
- "Test recipe" per target app

Device section:
- OEM status panel showing live pass/fail of each permission/setting check, with retry links:
  - Accessibility service enabled ✓/✗
  - Overlay permission granted ✓/✗
  - `POST_NOTIFICATIONS` granted ✓/✗ (Android 13+ only)
  - Battery optimization exempt ✓/✗
  - OEM-specific settings (Xiaomi autostart, etc.) — detected where possible, otherwise "verify manually" with a deep-link

About section:
- App version (versionName + versionCode)
- Build date
- Last recipe recording dates per target app
- Hidden dev menu: 7 taps on version string unlocks verbose logging, "force re-record" shortcuts, and log export.

### 8.4 Run History (mockup #4)

- Chronological list (most recent first): `date + time · number · cycles done/total · target app · status`
- Status badge: `done` (green), `stopped` (gray), `failed` (red)
- Tap a row → detail sheet showing the full `RunStepEvent` timeline for that run (useful for debugging which step failed on which cycle)
- "Clear all" button with confirmation dialog; also a "Clear older than 30 days" option
- `LazyColumn`-backed; no server pagination needed at expected volumes

---

## 9. First-Run Onboarding

Gated before the Dialer screen is usable. Each step verifies completion before proceeding.

1. **Welcome** — 1 paragraph explaining what AutoDial does and the permissions required.
2. **Grant Accessibility Service** — deep-link `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Verify on return via `AccessibilityManager.getEnabledAccessibilityServiceList().any { it.id contains BuildConfig.APPLICATION_ID }`.
3. **Grant Overlay** — deep-link `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. Verify via `Settings.canDrawOverlays(context)`.
4. **Grant `POST_NOTIFICATIONS`** (Android 13+ only, skipped on 11/12) — runtime permission.
5. **Battery optimization exemption** — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Verify via `PowerManager.isIgnoringBatteryOptimizations(packageName)`.
6. **OEM-specific setup** — if `Build.MANUFACTURER` is Xiaomi / Oppo / Vivo / Huawei, show the OEM-specific checklist (Section 12) with deep-link buttons and a "I've done this" confirmation for each item that can't be programmatically verified.
7. **Install target apps** — detect which of `com.b3networks.bizphone` and `finarea.MobileVoip` are installed via `PackageManager`; for each missing, show an install card with a sideload instruction. Re-check.
8. **Record BizPhone recipe** — launches the wizard (§6.2).
9. **Record Mobile VOIP recipe** — launches the wizard.
10. **Done** — proceed to Dialer Home.

Onboarding can be re-entered from Settings → "Re-run first-time setup". Individual steps can be run standalone (e.g. "Re-record BizPhone only").

---

## 10. Overlay Bubble

Displayed on top of the target app while a run is active. Hidden while AutoDial is in foreground (to avoid double-UI).

### 10.1 Implementation

- `WindowManager.addView` with `LayoutParams`:
  - `type = TYPE_APPLICATION_OVERLAY` (Android 8+; on 11+ this is the only non-privileged option)
  - `flags = FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED`
  - `format = TRANSLUCENT`
  - `gravity = TOP or CENTER_HORIZONTAL` initially; persisted position from DataStore
- Rendered via a `LifecycleOwner`-aware `ComposeView` wrapped in a dedicated `LifecycleRegistry` (since `WindowManager`-attached views don't have an owning activity).

### 10.2 Content

Compact (default, ~220 × 64 dp):
- Line 1: `3 / 10 · 22s`
- Line 2: `+65 67773777`
- Right side: red STOP chip (min 48 dp tap target)

Expanded (tap compact to expand, 3s auto-collapse):
- Progress ring (hang-up countdown)
- Current state sub-label
- Larger STOP button

### 10.3 Drag

Bubble is draggable via `onTouchEvent` on the root Compose view. On `ACTION_UP`, persist the new position to DataStore. No snapping; user positions it wherever.

### 10.4 Failure modes

Some OEMs (notably older MIUI) hide overlays under fullscreen/immersive/DND modes. This is an OEM bug, not something AutoDial can fully mitigate. When this happens, the operator can foreground AutoDial to see the full Active Run screen.

---

## 11. Android Version Compatibility (11 → 15)

Everyday reliability battle is Android 12 on Xiaomi/Oppo/Vivo (5 of 7 fleet phones). Test there first.

| Version | API | What matters | AutoDial handling |
|---|---|---|---|
| **11** | 30 | `<queries>` required to see other packages. Scoped storage (not relevant). Foreground-service types advisory. | Declare `<queries>` entries for both target apps in the manifest; otherwise `getLaunchIntentForPackage` returns null. |
| **12** | 31 | **BAL restrictions tighten** — starting activities from the background requires one of: foreground state, visible window, `SYSTEM_ALERT_WINDOW`, or accessibility-service context with `canPerformGestures`. Mandatory `android:exported` attribute on components with intent filters. Splash screen API mandatory. Notification trampolines banned. Exact alarms restricted (not used). | We never start activities from the background during a run (stay-in-target-app design). On the initial START, AutoDial is foreground, so launch is legal. We also hold `SYSTEM_ALERT_WINDOW` as belt-and-suspenders. All components `exported="false"` except `MainActivity`. Splash screen via `androidx.core:core-splashscreen`. |
| **13** | 33 | `POST_NOTIFICATIONS` becomes runtime permission, required for the foreground-service notification. `READ_MEDIA_IMAGES` replaces `READ_EXTERNAL_STORAGE` (not relevant). Themed icons optional. | Request `POST_NOTIFICATIONS` in onboarding step 4. If denied, run continues but notification is silent; a dismissible banner prompts re-grant on next app open. |
| **14** | 34 | **Foreground services must declare a type AND hold the matching permission.** Full-screen intents restricted. Implicit intents to internal components banned. | Service type: `dataSync` (semantic fit for "driving another app"). Matching permission: `FOREGROUND_SERVICE_DATA_SYNC`. Declared both in manifest and at `startForeground()`. Plan B if `dataSync` is ever rejected: `specialUse` with a justification attribute; but `dataSync` is fine for internal distribution. |
| **15** | 35 | **Edge-to-edge mandatory** when `targetSdk=35`. Predictive back gesture. **Stricter review of accessibility service descriptions** — vague descriptions can cause Android 15 to hide the service. 16 KB page size (not relevant). | `WindowCompat.setDecorFitsSystemWindows(false)` + inset-aware Compose layouts. Enable predictive back via `android:enableOnBackInvokedCallback="true"`. Write a clear accessibility description (see §14.2). |

**Accessibility service flags (`accessibility_config.xml`):**
```xml
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewClicked|typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:notificationTimeout="100"
    android:packageNames="com.b3networks.bizphone,finarea.MobileVoip,<app's own package during record mode>"
    android:description="@string/accessibility_service_description" />
```

**What we explicitly do NOT use** (to stay within unprivileged-app territory): root, Shizuku, `BIND_DEVICE_ADMIN`, system signature, `INTERACT_ACROSS_USERS`.

---

## 12. OEM Mitigations

The "works for a while, then stops" failure pattern is OEM battery managers silently revoking permissions or killing the accessibility service. This section is the only thing keeping the app reliable on Xiaomi / Oppo / Vivo.

### 12.1 In-app OEM detection

`OemCompatibilityHelper` identifies the OEM via `Build.MANUFACTURER` (case-insensitive: `"xiaomi"`, `"redmi"`, `"poco"` → Xiaomi; `"oppo"`, `"realme"` → Oppo; `"vivo"`, `"iqoo"` → Vivo; `"samsung"` → Samsung; others → Generic). For each OEM it exposes:
- A list of required settings items (human-readable)
- For each item: whether it's programmatically verifiable, and if so, the verification logic
- For each item: an `Intent` to deep-link into the closest settings screen, where the OEM exposes one

### 12.2 Per-OEM required settings

**Xiaomi (MIUI / HyperOS)** — worst offender. ALL of:
1. **Autostart** — Security → Permissions → Autostart → enable AutoDial.
   Deep-link: `ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")`.
2. **Battery saver** — Settings → Battery → App battery saver → AutoDial → "No restrictions".
   Deep-link: action `miui.intent.action.POWER_HIDE_MODE_APP_LIST`.
3. **Show on Lock screen** — Settings → Apps → AutoDial → Other permissions → Show on Lock screen → allow. (Required so overlay appears if screen has slept.)
4. **Lock in Recents** — open Recents, pull down on the AutoDial card (or tap the lock icon on the card). Prevents swipe-kill and memory-pressure kill.
5. **MIUI optimization OFF** (last-resort only) — Settings → Additional settings → Developer options → MIUI optimization → OFF. Controversial; include as optional troubleshooting step.

**Oppo (ColorOS / realme UI):**
1. **Auto launch** — Settings → Apps → Auto launch → enable AutoDial.
2. **App power management** — Settings → Battery → Power saving → App power management → AutoDial → enable "Allow background activity", "Allow auto-launch", "Allow running in background".
3. **Lock in Recents**.
4. **High background power consumption** — Battery → High background power consumption → AutoDial → allow.

**Vivo (FunTouch / OriginOS / iQOO):**
1. **Autostart** — Settings → More settings → Permission management → Autostart → AutoDial → enable.
2. **Background power consumption** — Settings → Battery → Background power consumption management → AutoDial → allow.
3. **High-refresh whitelist** (if present) — add AutoDial.
4. **Lock in Recents**.

**Samsung (One UI):**
1. Settings → Apps → AutoDial → Battery → Unrestricted.
2. Settings → Device care → Battery → Background usage limits → Never sleeping apps → add AutoDial.

**Generic / Pixel / Stock Android:**
1. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — handled by onboarding step 5. No additional OEM steps needed.

### 12.3 Runtime self-healing

While a run is active, every 30 seconds the AccessibilityService performs a self-check:
- Still bound? `AccessibilityManager.getEnabledAccessibilityServiceList()` contains us?
- Overlay still receiving WindowManager updates? (Attempt a no-op layout update; catch `BadTokenException`.)
- Foreground service still alive? (Implicit — this code is running inside it.)

On self-check failure: log to `RunStepEvent` with reason (`accessibility-service-revoked` / `overlay-token-invalid` / `fgs-killed`), transition to `FAILED`, and attempt one last hangup via coordinate-only tap.

On next app open: banner calls out the failure and deep-links to the fix.

---

## 13. Data Model

### 13.1 Room entities

```kotlin
@Entity(tableName = "recipes")
data class Recipe(
  @PrimaryKey val targetPackage: String,         // "com.b3networks.bizphone" or "finarea.MobileVoip"
  val displayName: String,                        // "BizPhone" | "Mobile VOIP"
  val recordedVersion: String,                    // target app's versionName at record time
  val recordedAt: Long,                           // epoch millis
  val schemaVersion: Int                          // bump when we add new steps
)

@Entity(
  tableName = "recipe_steps",
  primaryKeys = ["targetPackage", "stepId"],
  foreignKeys = [ForeignKey(
    entity = Recipe::class,
    parentColumns = ["targetPackage"],
    childColumns = ["targetPackage"],
    onDelete = ForeignKey.CASCADE
  )]
)
data class RecipeStep(
  val targetPackage: String,
  val stepId: String,                             // "OPEN_DIAL_PAD", "DIGIT_0".."DIGIT_9", "PRESS_CALL", "HANG_UP_CONNECTED", "HANG_UP_RINGING", "RETURN_TO_DIAL_PAD"
  val resourceId: String?,
  val text: String?,
  val className: String?,
  val boundsRelX: Float, val boundsRelY: Float,
  val boundsRelW: Float, val boundsRelH: Float,
  val screenshotHashHex: String?,
  val recordedOnDensityDpi: Int,
  val recordedOnScreenW: Int,
  val recordedOnScreenH: Int
)

enum class RunStatus { DONE, STOPPED, FAILED }

@Entity(tableName = "runs")
data class RunRecord(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long,
  val endedAt: Long,
  val number: String,
  val targetPackage: String,
  val plannedCycles: Int,                         // 0 = spam mode
  val completedCycles: Int,
  val hangupSeconds: Int,
  val status: RunStatus,
  val failureReason: String?
)

@Entity(
  tableName = "run_step_events",
  foreignKeys = [ForeignKey(
    entity = RunRecord::class,
    parentColumns = ["id"],
    childColumns = ["runId"],
    onDelete = ForeignKey.CASCADE
  )]
)
data class RunStepEvent(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val runId: Long,
  val cycleIndex: Int,
  val stepId: String,
  val at: Long,
  val outcome: String,                            // "ok:node-primary", "ok:node-fallback", "ok:coord-fallback", "failed:timeout", "failed:hash-mismatch", "failed:target-closed"
  val detail: String?
)
```

### 13.2 DataStore Proto — user settings

```proto
message Settings {
  int32 default_hangup_seconds = 1;               // default 25
  int32 default_cycles = 2;                       // default 10
  string default_target_package = 3;              // default "com.b3networks.bizphone"
  int32 spam_mode_safety_cap = 4;                 // default 9999
  int32 inter_digit_delay_ms = 5;                 // default 400
  int32 overlay_x = 6;
  int32 overlay_y = 7;
  int64 onboarding_completed_at = 8;              // 0 if incomplete
  bool verbose_logging_enabled = 9;               // toggled via hidden dev menu
}
```

### 13.3 Repositories

- `RecipeRepository` — CRUD on recipes + steps, exposes `Flow<Recipe?>` per package for UI observation.
- `HistoryRepository` — insert, query, clear-all, clear-older-than.
- `SettingsRepository` — DataStore Proto wrapper, exposes `Flow<Settings>`.

---

## 14. Permissions and Manifest

### 14.1 Permissions (`AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<queries>
  <package android:name="com.b3networks.bizphone" />
  <package android:name="finarea.MobileVoip" />
</queries>
```

**Not requested:** `CALL_PHONE`, `READ_PHONE_STATE`, `RECORD_AUDIO`, `CAMERA`, `INTERNET`, any contacts/SMS permission. AutoDial never touches the telephony stack — BizPhone and Mobile VOIP do. `INTERNET` is intentionally omitted; the app works fully offline.

**`REQUEST_INSTALL_PACKAGES`** — NOT declared. The provisioner installs APKs manually by tapping the downloaded file; AutoDial itself never invokes the installer.

### 14.2 Accessibility service description string

Required by Android 15 review. `strings.xml`:

```xml
<string name="accessibility_service_description">AutoDial uses Android\'s accessibility service to tap buttons inside BizPhone and Mobile VOIP on your behalf, placing and ending VoIP calls as configured by the sales team. It only interacts with the buttons you teach it during the setup wizard and does not read or transmit any other screen content.</string>
```

### 14.3 Manifest components

- `MainActivity` — `exported="true"` with LAUNCHER intent filter
- All other activities — `exported="false"`
- `RunForegroundService` — `exported="false"`, `foregroundServiceType="dataSync"`
- `AutoDialAccessibilityService` — `exported="true"` (required by Android for accessibility services to be enable-able), `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
- No `BroadcastReceiver` with implicit intent filters (banned on 14+)

### 14.4 Wake lock

`RunForegroundService.onStartCommand()`:

```kotlin
val pm = getSystemService(POWER_SERVICE) as PowerManager
wakeLock = pm.newWakeLock(
  PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE or PowerManager.ACQUIRE_CAUSES_WAKEUP,
  "AutoDial:run"
)
wakeLock.setReferenceCounted(false)
wakeLock.acquire(/* no timeout — released explicitly on run end */)
```

Released in `onDestroy` and on run end. Phone is always plugged in during runs per operator practice, so full-brightness wake lock is acceptable.

---

## 15. Distribution

**Release mode:** sideload only. No Play Store.

**Signing:** v2 + v3 signature schemes. Single release keystore held by the operator. `app/keystore.properties` committed with structure only, secrets injected via local properties or CI environment variables. Debug builds use the default debug keystore.

**Versioning:**
- `versionName` — semantic (e.g. `1.2.0`), displayed in About.
- `versionCode` — monotonically increasing integer, incremented per build.

**No in-app update check** (per user preference). Operator distributes new APKs manually via email / shared drive. To preserve existing installs, the `minSdkVersion` and signing certificate must not change without deliberate migration.

**Target-app auto-update prevention** — enforced at the device level during provisioning:
- Play Store → profile → Manage apps & device → tap BizPhone → ⋮ → uncheck "Enable auto update". Repeat for Mobile VOIP.
- Or: Play Store → Settings → Network preferences → Auto-update apps → "Don't auto-update apps" (device-wide, preferred for single-purpose fleet phones).

Documented in the Device Provisioning Checklist (Appendix A).

**Build variants:**
- `debug` — extra logging, hidden dev menu always on, "force re-record recipe" shortcut
- `release` — stripped, minified with R8; keep rules preserve accessibility service classes, Room entities, and Compose metadata

---

## 16. Testing Strategy

### 16.1 Unit tests (JVM)

- Recipe JSON (de)serialization round-trip
- State machine transitions — happy path + each failure path
- Number input validation & normalization (strip non-digits, length limits)
- `OemCompatibilityHelper` returns correct `Intent` per `Build.MANUFACTURER`
- Perceptual hash Hamming distance thresholds (golden-image comparisons)
- Relative-bounds scaling math across different screen sizes and densities

### 16.2 Instrumentation tests (Android, emulator, API 30 + 31)

A stub target-app Activity ships in `src/androidTest/` with a fake dial pad. Instrumentation tests exercise:
- Full wizard flow: open stub app → record each step → recipe is saved correctly
- Full run flow: load saved recipe → run 2 cycles on stub app → verify state machine transitions
- Accessibility service receives events and dispatches actions
- Overlay bubble appears, is draggable, STOP works
- Re-record flow wipes and re-captures recipe

### 16.3 Manual E2E matrix (pre-release)

Spreadsheet in repo: `docs/testing/fleet-matrix.md`. 7 phones × 2 target apps = 14 cells. Each cell:
1. Record recipe via wizard
2. Run "Test recipe" — all steps pass
3. Real 3-cycle run on a cooperating test number — history shows `DONE`

Operator fills in the spreadsheet before each release. Any cell failure blocks release.

### 16.4 Drift regression

Snapshots of known-good recipes (per phone, per app) checked into `docs/testing/recipe-snapshots/`. Before shipping: run "Test recipe" on each fleet phone against its current recipe. Any step falling back to coordinate fallback (`outcome = "ok:coord-fallback"`) is a yellow flag — target app may have updated; investigate.

---

## 17. Risks

**High:**

1. **Target apps strip resource IDs in release builds.** If BizPhone or Mobile VOIP's release builds obfuscate or drop `resourceId` values, primary fingerprinting (Tier 1) won't work; we rely on Tier 2 (text+class+bounds) and Tier 3 (coordinate fallback). Mitigation: wizard shows a warning at record time when `resourceId` is null, so the engineer learns this on day one.

2. **Target apps update their UI and recipes break silently.** Mitigation: provisioning checklist disables Play Store auto-updates. Belt-and-suspenders: on app start, compare `recordedVersion` in `Recipe` against the installed target app's current `versionName`; if different, show a banner prompting re-record before next run.

3. **OEM silently revokes accessibility after a system update.** The "works 1-2 months then stops" failure pattern. Cannot be fully prevented — it is OEM behavior. Mitigation: runtime 30s self-check during runs + on-startup accessibility-enabled verification + clear banner with deep-link when revoked.

**Medium:**

4. **Mobile VOIP's dial-pad flow may differ from BizPhone's** in ways not visible until first recording (e.g., modal confirmation before call, login gate, different hangup button layout). Mitigation: recipes are recorded independently; no shared assumptions between apps.

5. **Target app in a non-ready state** — BizPhone's SIP session expired, or Mobile VOIP needs a re-login. Pressing Call may show an error dialog instead of placing the call. V1 mitigation: `PRESS_CALL → IN_CALL` timeout (3s) detects this → run fails with reason `target-app-not-ready`. V2 could parse the error dialog node.

6. **Simultaneous run attempts.** UI disables START while `RunForegroundService.isActive`; service-side check rejects duplicate `start()` calls with a toast.

7. **Overlay hidden by DND / fullscreen / immersive modes.** Some OEM bugs prevent `TYPE_APPLICATION_OVERLAY` from showing in these states. Mitigation: operator can foreground AutoDial for the full Active Run screen when this happens.

**Low (flagged for awareness):**

8. **ANR if screenshot hashing blocks the binder thread.** `takeScreenshot()` and pHash computation must run on `Dispatchers.Default` (or a dedicated background dispatcher), never on the accessibility service's main thread.

9. **Recipes are NOT portable across phones.** Different screen sizes/densities mean each phone records its own recipe. Documented in provisioning checklist; no code change required.

10. **Android 12 BAL edge cases.** If a third-party launcher or overlay app steals foreground momentarily during START, our foreground state may lapse. Mitigation: hold `SYSTEM_ALERT_WINDOW` + launch target app immediately in the START handler, no awaited async work in between.

---

## 18. Out of Scope (v1)

- WhatsApp target app support
- iOS
- Multi-number queue / CSV import
- Remote control or web dashboard
- Server-side telemetry
- Call recording / transcription / voicemail detection
- Multiple concurrent runs on one device
- Root / Shizuku / device-admin variants
- Play Store distribution

Data model (`RunRecord`) is designed to be extensible to a multi-number queue later without breaking schema — a `batchId` column can be added non-destructively.

---

## 19. Open Questions

1. **Release keystore custody policy.** Who holds it? Where is it backed up? Rotation policy? Not a code decision; needs process decision before v1.0.
2. **Hidden dev menu scope.** Current spec includes it (7-tap on version string). Recommended: keep it in release builds. Costs ~10 lines; saves hours of remote debugging. **Default: included.**
3. **Spam-mode safety cap editability.** Current spec: user-editable in Settings (default 9,999). Alternative: hard-coded so sales users can't raise it. **Default: editable (internal tool).**

---

## Appendix A — Device Provisioning Checklist

One-time per new fleet phone. Provisioner performs in order:

**Pre-setup:**
- [ ] Charge phone to at least 50%.
- [ ] Set phone language and timezone.
- [ ] Sign into Google account (if needed for Play Store).

**Play Store:**
- [ ] Open Play Store → Settings → Network preferences → Auto-update apps → "Don't auto-update apps".
- [ ] Install BizPhone from Play Store (or sideload).
- [ ] Install Mobile VOIP from Play Store (or sideload).
- [ ] Verify both apps are NOT set to auto-update.

**AutoDial install:**
- [ ] Sideload the latest AutoDial APK.
- [ ] Open AutoDial. The onboarding flow begins.

**Onboarding permissions (AutoDial guides these):**
- [ ] Enable AutoDial accessibility service.
- [ ] Grant overlay permission.
- [ ] Grant notifications permission (Android 13+ only).
- [ ] Exempt AutoDial from battery optimization.

**OEM-specific (done in order shown in-app for this phone's make):**
- [ ] Xiaomi: autostart on, battery saver "No restrictions", show on lock screen on, lock in Recents.
- [ ] Oppo: auto launch on, app power management all 3 toggles on, lock in Recents.
- [ ] Vivo: autostart on, background power consumption allowed, lock in Recents.
- [ ] Samsung: battery "Unrestricted", "Never sleeping apps" includes AutoDial.
- [ ] Generic: no additional OEM steps.

**Sign into target apps:**
- [ ] Open BizPhone, sign in to the sales team's account. Verify you can place a call manually.
- [ ] Open Mobile VOIP, sign in similarly. Verify manual call.

**Record recipes (AutoDial guides these):**
- [ ] Run setup wizard for BizPhone (follow in-app prompts).
- [ ] Run setup wizard for Mobile VOIP.

**Verify:**
- [ ] Settings → Test recipe → BizPhone → all steps pass.
- [ ] Settings → Test recipe → Mobile VOIP → all steps pass.
- [ ] Place a real 3-cycle test run on a cooperating number. History shows `DONE`.

**Lock down:**
- [ ] Lock AutoDial in Recents.
- [ ] Place sticker on back of phone with provisioning date + provisioner initials.

---

## Appendix B — Glossary

- **API level** — integer identifier for an Android version. API 30 = Android 11.
- **Accessibility Service** — Android system component with elevated privileges to read another app's UI tree and perform taps/gestures on its behalf. Requires explicit user grant in system settings.
- **BAL (Background Activity Launch)** — Android 12+ restriction preventing background apps from launching activities unless specific conditions are met.
- **FGS (Foreground Service)** — long-running background task with a persistent notification. Harder for the OS to kill than a regular service.
- **Node** — a view in the accessibility tree, represented at runtime by `AccessibilityNodeInfo`.
- **OEM skin** — manufacturer-specific Android variant (MIUI = Xiaomi, ColorOS = Oppo, FunTouch/OriginOS = Vivo, One UI = Samsung).
- **Recipe** — stored UI interaction map for a target app. Records how to tap each required button.
- **pHash (perceptual hash)** — a fuzzy image fingerprint. Similar images produce similar hashes; used to verify we tapped the right place.
- **Relative bounds** — (x, y, w, h) normalized to 0..1 of screen size, so recording on one device still maps onto another device's screen.
- **Tier 1 / 2 / 3 / 4** — the Player's cascading resolution strategy for tapping a recorded element. 1 = resourceId, 2 = text+class+bounds, 3 = coordinates, 4 = verification.
- **Wake lock** — a power-management handle that keeps the CPU (and optionally screen) awake. Held for the duration of a run.
