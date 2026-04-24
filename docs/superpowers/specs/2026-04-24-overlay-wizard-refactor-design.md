# AutoDial — Overlay Wizard Refactor (Design)

**Date:** 2026-04-24
**Status:** Approved, ready for implementation plan

## Summary

Replace the current screen-switching wizard (user alternates between AutoDial and the target app between every recording) with an **interactive overlay wizard** where the user stays in the target app the whole time. AutoDial's overlay guides them step-by-step, captures taps via the existing `UiRecorder`, persists the recipe on completion, and stays out of the way otherwise.

This refactor also fixes the Mobile VOIP `HANG_UP` setup blocker: the call and hang-up buttons sit at the same on-screen location but are different views. Instead of recording `HANG_UP` during a test call, runtime will reuse `PRESS_CALL`'s step data (Tier-3 coordinate playback hits the same spot).

## Motivation

Three problems with the current wizard:

1. **Context switching is disorienting.** User opens AutoDial → reads step prompt → taps "Start recording" → app switches to BizPhone → taps target → comes back to AutoDial → repeat. 5–14 round-trips depending on target. Users lose track of where they are.
2. **Mobile VOIP can't be set up.** The `HANG_UP` step requires tapping a button that only appears during an active call, and the button is positionally identical to the call button that was just pressed. The current wizard has no way to record it.
3. **OPPO ColorOS kills `MainActivity`** aggressively while the target app is foregrounded. Each context switch risks Activity destruction, broken state, and the "return to AutoDial → black screen" class of bugs we've already debugged elsewhere.

The overlay approach eliminates all three: no context switching during setup, Mobile VOIP hangup becomes a runtime-only concern, and `MainActivity` is no longer in the wizard's critical path.

## User flow

**Entry.** User taps "Set up BizPhone" (or Mobile VOIP) on the Dialer. AutoDial launches the target app immediately and calls `AutoDialAccessibilityService.beginWizard(pkg)`. The overlay card appears on top of the target app with step 1 armed. `WizardScreen` is removed from the nav graph — no intermediate Compose screen in AutoDial.

**The 4 macro-steps** (same for both BizPhone and Mobile VOIP after this refactor):

1. **`OPEN_DIAL_PAD`** — card: *"Step 1 of 4 — Tap the dial pad / keypad icon."* User taps it in target app. Capture fires, ✓ flashes for ~500 ms, auto-advance.
2. **`RECORD_DIGITS`** — card: *"Step 2 of 4 — Tap digits 0, 1, 2, … 9 in order."* Card body shows a `0 ✓ 1 ✓ 2 _ …` chip row updating in real time. Strict order. `UiRecorder` queues `DIGIT_0..DIGIT_9`. If `digitAutoDetected` fires (resourceId/text reveals which digit was tapped), re-slot the capture, but the prompt assumes strict order. All 10 captured → auto-advance.
3. **`CLEAR_DIGITS`** — card: *"Step 3 of 4 — Tap backspace/clear once. AutoDial will auto-wipe the dial pad after."* User taps, capture fires, existing `autoWipeDialPad` logic runs (15 programmatic taps to empty the dial pad), advance.
4. **`PRESS_CALL`** — card: *"Step 4 of 4 — IMPORTANT: turn on airplane mode first (so the call fails instantly and we can read the button). Then tap the call bar."* Capture fires; existing `findDuplicateWarning` check runs; if clean → success flash → wizard completes. If duplicate → `DuplicateWarning` state, user re-records.

Mobile VOIP uses the same 4 steps — no `HANG_UP` recording. Runtime substitutes `PRESS_CALL`'s step data for hang-up (see **Runtime playback changes** below).

**Mid-wizard interactions.**
- **Undo button** (always visible on card): removes most recent capture and steps back. Cross-macro-step — if user is at `CLEAR_DIGITS` start, undo pops back to digit 10/10. If user is at digit 5/10, undo pops to 4/10.
- **Cancel ✕** (always visible on card): single tap, everything discarded, overlay dismisses, user stays in target app. Existing recipe (if any) is untouched since nothing was persisted yet.
- **Draggable card** — same pattern as the current run-overlay bubble: a drag surface on the card body (not on the buttons), user moves the card out of the way if it obscures something they need to tap.
- **User leaves target app** (home, notification, etc.): overlay stays alive (it's a system `TYPE_APPLICATION_OVERLAY`), card updates to *"Return to {app name} to continue."* When accessibility sees target app foreground again, card reverts to current-step prompt. No state lost.

**Completion.** After `PRESS_CALL` (and no duplicate warning), `WizardRecipeWriter.save(...)` writes to Room via `RecipeRepository.saveRecipe`. Card flashes *"✓ Recipe saved — ready to use"* for ~2 s, then auto-dismisses. User stays in target app. They return to AutoDial via launcher/app switcher when ready (matches run-end behavior decided earlier).

## Architecture

**Three new files, four modified, two deleted.**

### New files

- **`wizard/WizardStateMachine.kt`** — plain class (not a `ViewModel`). Analog to `RunStateMachine`. Owns `StateFlow<WizardState>`, processes commands and events. Pure logic — unit-testable without Android. Replaces the state half of the current `WizardViewModel`.
- **`wizard/WizardState.kt`** — sealed class hierarchy (see **WizardStateMachine** section below).
- **`overlay/WizardOverlayController.kt`** — separate from the existing `OverlayController` (which stays as the run-status bubble). Renders the wizard corner card via `WindowManager.addView(ComposeView)` with `TYPE_APPLICATION_OVERLAY`. Exposes `show()`, `updateState(WizardState)`, `dismiss()`. Internally hosts a `WizardCard` composable that draws prompt / digit chip row / Undo / Cancel.
- **`wizard/WizardRecipeWriter.kt`** — small helper, 30ish lines. Takes the `Map<String, RecordedStep>` accumulated by the state machine plus a `targetPackage` and writes a `Recipe` + `List<RecipeStep>` via the injected `RecipeRepository`. Extracted for testability (no coupling to Compose or services).

### Modified files

- **`accessibility/AutoDialAccessibilityService.kt`** — adds `beginWizard(pkg)` / `endWizard()`. Owns a `WizardStateMachine` + `WizardOverlayController` pair. Wires `UiRecorder.capturedSteps` (a Flow) into `stateMachine.onCapture(...)`. Observes `WINDOW_STATE_CHANGED` for the active wizard target package to fire `TargetBackgrounded` / `TargetForegrounded` events into the state machine. Subscribes to `stateMachine.state` and drives side-effects: arming `UiRecorder`, triggering `autoWipeDialPad`, calling `WizardRecipeWriter` on `Completed`, dismissing the overlay on terminal states.
- **`model/TargetApp.kt`** — replace `autoHangupResourceId(pkg): String?` with a sealed `HangupStrategy` and `hangupStrategy(pkg): HangupStrategy` (details in **Runtime playback changes** below).
- **`service/RunForegroundService.kt`** — the `RunState.InCall` branch's hang-up dispatch switches from checking `autoHangupResourceId` to matching on `hangupStrategy`. One site, ~8 lines changed.
- **`ui/dialer/DialerScreen.kt`** — the Setup button for an unrecorded target currently navigates to `Screen.Wizard(pkg)`. Change it to call `AutoDialAccessibilityService.instance?.beginWizard(pkg)` and stay on the Dialer. Permission check remains gated by `startBlockReason`.
- **`ui/navigation/NavGraph.kt`** — remove the `Wizard` composable route.

### Deleted files

- **`ui/wizard/WizardScreen.kt`** — no more in-app wizard screen.
- **`ui/wizard/WizardViewModel.kt`** — logic splits between `WizardStateMachine` (state) and `WizardRecipeWriter` (persistence).

### Data flow during a wizard session

```
Dialer tap
  → AutoDialAccessibilityService.beginWizard(pkg)
     ├─ launches target app via Intent
     ├─ WizardStateMachine.onCommand(Start(pkg))  →  state = Step(OPEN_DIAL_PAD, queue=[OPEN_DIAL_PAD], captured={}, …)
     └─ WizardOverlayController.show() + updateState(...)

user taps target in target app
  → UiRecorder.onEvent(TYPE_VIEW_CLICKED) fires capture via Channel
  → AutoDialAccessibilityService collects Channel
  → stateMachine.onCapture(stepId, RecordedStep)
  → stateMachine advances queue / macro / overall step
  → state flow emits new WizardState
  → WizardOverlayController.updateState(...) re-renders card
  → (if CLEAR_DIGITS just captured) side-effect: autoWipeDialPad(...)

on Completed
  → WizardRecipeWriter.save(pkg, captured) → RecipeRepository.saveRecipe
  → card flashes "Recipe saved", dismisses 2s later
  → AutoDialAccessibilityService.endWizard() clears state + overlay
```

### Why `AutoDialAccessibilityService` is the owner

- It already hosts `UiRecorder` so the capture channel is local.
- It already renders overlays via `context.getSystemService(WINDOW_SERVICE)`.
- It survives `MainActivity` being killed — which is exactly when we need it to keep going.
- Its lifecycle matches the wizard's lifecycle cleanly: wizard can never be active when accessibility isn't.

## WizardStateMachine

Pure logic. No Android dependencies. `StateFlow<WizardState>` out; commands and events in.

### State shape

```kotlin
sealed class WizardState {
    object Idle : WizardState()

    data class Step(
        val targetPackage: String,
        val macro: MacroStep,
        val queue: List<String>,       // remaining stepIds for this macro, e.g. [DIGIT_3,...,DIGIT_9]
        val captured: Map<String, RecordedStep>,  // all captures across all macros so far
        val undoStack: List<String>,   // stepIds in capture order, for Undo
        val lastCapture: RecordedStep? // drives the ✓ flash + UI preview
    ) : WizardState()

    data class AwaitingReturn(val resume: Step) : WizardState()

    data class DuplicateWarning(
        val stepId: String,
        val message: String,
        val resume: Step
    ) : WizardState()

    data class Completed(
        val recipeSaved: Boolean?,     // null = save in progress, true/false = save result
        val error: String? = null
    ) : WizardState()

    object Cancelled : WizardState()
}

enum class MacroStep { OPEN_DIAL_PAD, RECORD_DIGITS, CLEAR_DIGITS, PRESS_CALL }
```

### Commands (from the overlay UI)

- `Start(targetPackage)` — from `Idle`, becomes `Step(OPEN_DIAL_PAD, queue=[OPEN_DIAL_PAD], captured={}, …)`.
- `Cancel` — from any active state, becomes `Cancelled`.
- `Undo` — pops last entry off `undoStack`, removes it from `captured`, rebuilds `queue` / `macro` to reflect the earlier state. Rebuild rule: macro = the macro containing the last remaining capture (or `OPEN_DIAL_PAD` if `undoStack` is empty).
- `ReRecord` — from `DuplicateWarning`, clears `captured[PRESS_CALL]`, state becomes `Step(PRESS_CALL, queue=[PRESS_CALL], …)`.

### Events (from the accessibility layer)

- `Captured(RecordedStep)` — add to `captured`, remove matching stepId from `queue`, append stepId to `undoStack`. If `queue` now empty: advance macro. On `PRESS_CALL` capture, run `findDuplicateWarning` — if duplicate, go to `DuplicateWarning`; else if all 4 macros are done, state becomes `Completed(recipeSaved=null)` (transient "saving" state) and the accessibility service kicks off `WizardRecipeWriter.save(...)` as a side-effect.
- `TargetBackgrounded` — from `Step(…)`, becomes `AwaitingReturn(resume=current)`. `UiRecorder.stopCapturing()` called externally as a side-effect.
- `TargetForegrounded` — from `AwaitingReturn(resume)`, becomes `resume`. `UiRecorder.startCapturing(queue, pkg)` restarted externally.
- `ServiceRevoked` — from any active state, becomes `Cancelled` with an error (toast rendered by overlay).
- `RecipeSaveResult(success: Boolean, error: String? = null)` — from `Completed(recipeSaved=null)`, becomes `Completed(recipeSaved=success, error=error)`. The overlay renders the transient null state as a brief "Saving…" indicator; the 2 s auto-dismiss timer starts once the result arrives (or immediately on failure, since the user needs to see the Retry button).

### Macro advance rule

```
OPEN_DIAL_PAD done    → RECORD_DIGITS, queue = DIGIT_0..DIGIT_9 (strict order)
RECORD_DIGITS done    → CLEAR_DIGITS, queue = [CLEAR_DIGITS]
CLEAR_DIGITS done     → trigger autoWipeDialPad side-effect → PRESS_CALL, queue = [PRESS_CALL]
PRESS_CALL done       → duplicate check → Completed (after save) or DuplicateWarning
```

Partial captures live only in-memory. If the user cancels or the process dies, nothing is written — matches existing behavior.

### What's *not* in the state machine

- Side-effects (launching the target app, arming `UiRecorder`, auto-wiping, saving to Room) — coordinated by the accessibility service reacting to state changes.
- UI rendering — done by `WizardOverlayController` subscribing to `StateFlow<WizardState>`.

This isolation makes the state machine fully unit-testable, same pattern as `RunStateMachineTest`.

## Runtime playback changes (Mobile VOIP hangup)

Only one runtime change. `TargetApp.kt`'s hangup helper is generalized:

```kotlin
sealed class HangupStrategy {
    // Tap the node with this resourceId that is visible+clickable (BizPhone).
    data class AutoHangupById(val resourceId: String) : HangupStrategy()
    // Execute the recipe's PRESS_CALL step again — the end-call button sits
    // at the same on-screen location (Mobile VOIP).
    object ReuseCallButton : HangupStrategy()
    // Default — execute the recorded HANG_UP step.
    object RecordedStep : HangupStrategy()
}

fun hangupStrategy(targetPackage: String): HangupStrategy = when (targetPackage) {
    BIZPHONE    -> HangupStrategy.AutoHangupById("com.b3networks.bizphone:id/clearCallButton")
    MOBILE_VOIP -> HangupStrategy.ReuseCallButton
    else        -> HangupStrategy.RecordedStep
}
```

In `RunForegroundService.driveState` under `RunState.InCall`, the existing branch:

```kotlin
val autoId = TargetApps.autoHangupResourceId(state.params.targetPackage)
if (autoId != null) autoHangup(autoId, accessService)
else executeStep("HANG_UP", state.params, accessService)
```

becomes:

```kotlin
when (val strategy = TargetApps.hangupStrategy(state.params.targetPackage)) {
    is HangupStrategy.AutoHangupById -> autoHangup(strategy.resourceId, accessService)
    is HangupStrategy.ReuseCallButton -> executeStep("PRESS_CALL", state.params, accessService,
        logicalStepId = "HANG_UP")
    HangupStrategy.RecordedStep -> executeStep("HANG_UP", state.params, accessService)
}
```

The `ReuseCallButton` branch passes an optional `logicalStepId` to `executeStep` so run history still records the event as `HANG_UP` for diagnostics, even though the step data came from `PRESS_CALL`.

## Data & persistence

**No schema change.** Room's `Recipe` and `RecipeStep` entities stay identical. The DB holds 4 steps for Mobile VOIP instead of 5 — no migration needed because old Mobile VOIP installs couldn't complete the wizard anyway (that's the bug we're fixing).

**Existing BizPhone recipes are untouched.** They continue to work via `HangupStrategy.AutoHangupById`.

**Recipe writing happens in one place.** `RecipeRepository.saveRecipe(recipe, steps)` called from `WizardRecipeWriter` at the `Completed` transition. Same write pattern as today, just invoked from the state machine's terminal side-effect rather than from `WizardViewModel.saveRecipe`.

## Error handling & edge cases

- **Accessibility service revoked mid-wizard.** Existing 30 s `startSelfCheckLoop` already runs during accessibility activity; extend it to fire `ServiceRevoked` during wizard too. State machine goes to `Cancelled`; overlay shows a toast and dismisses.
- **Recipe save fails (Room write error).** `Completed(recipeSaved=false, error=message)`. Overlay card shows the error with "Retry" (retriggers `saveRecipe`) and "Cancel" (discards). Rare but non-fatal.
- **Duplicate PRESS_CALL capture** (BizPhone's fast-transition failure). Existing `findDuplicateWarning` logic is preserved. `DuplicateWarning` state holds the message; card shows it; "Re-record" button wipes PRESS_CALL and rearms.
- **User rotates device mid-wizard.** Overlay X/Y stored in `overlayX/overlayY`. On config change, re-read screen dimensions and clamp position inside new bounds.
- **`UiRecorder` capture with missing resourceId.** Already handled — flag on `RecordedStep`, surfaced as a small banner on the next card ("⚠ no resourceId — playback may be coordinate-only"). Non-blocking.
- **Target app crashes / force-closes during wizard.** Same as user leaving — `TargetBackgrounded` → `AwaitingReturn`. If target app is restarted, resume. If user gives up, Cancel.
- **Wizard re-run while existing recipe exists.** Starting a wizard for a target that already has a recipe doesn't touch the existing recipe until the new wizard completes. Partial captures never overwrite. On `Completed`, `RecipeRepository.saveRecipe` handles upsert.
- **Overlay permission revoked during wizard.** `WindowManager.addView` throws; caught → state = `Cancelled` with toast.

## Out of scope

- **UI refactor to match `AutoDial.html` mockup.** Separate future pass. The wizard card uses the existing dark theme and is styled to match the mockup's dark-utilitarian look, but the Dialer / ActiveRun / Settings / History screens are untouched in this work.
- **App icon replacement** (user-provided assets in `UI Design/`). Minor follow-up, not part of this refactor.
- **Multi-select digit grid or fuzzy order.** Strict 0→9 order. If digit auto-detect succeeds the backing slot is re-routed, but the prompt and visible chips assume strict order.
- **Test-call automation for Mobile VOIP hangup.** Skipped in favor of `ReuseCallButton` strategy. Revisit only if Tier-3 coord playback proves unreliable in the field.
- **Wizard for target apps other than BizPhone / Mobile VOIP.** Not added. Future targets extend via `hangupStrategy(pkg)`.
- **Partial-capture persistence across wizard sessions.** If the wizard is cancelled or the process dies, captures are lost. Cheap enough to redo.
- **Per-recipe re-record of a single step.** Can't re-record just `DIGIT_7` without redoing the whole wizard. Undo handles the common "I tapped wrong" case.

## Testing

### Unit tests (`app/src/test/`)

`WizardStateMachineTest` — same pattern as `RunStateMachineTest`. Cases:

- Start transitions through macros in order.
- Undo at each macro boundary (digit 5 → 4; clear → digit 10; press_call → clear; etc.).
- Duplicate warning on PRESS_CALL; ReRecord clears and rearms PRESS_CALL only.
- Cancel from each state transitions to `Cancelled`.
- TargetBackgrounded + TargetForegrounded round-trip preserves the exact Step.
- Capture arriving while `AwaitingReturn` is discarded (sanity check).
- RecipeSaveResult success → `Completed(recipeSaved=true)`, failure → `Completed(recipeSaved=false, error=…)`.

Pure-logic tests; no Android dependencies; `runTest` with the existing setup.

### Device / manual tests (OPPO CPH2471)

- **Happy path — BizPhone.** Fresh install, complete 4 macros, verify recipe persists, run 2 cycles of a real call end-to-end.
- **Happy path — Mobile VOIP.** Fresh install, complete 4 macros (no HANG_UP step), start a real run — hang-up fires via `ReuseCallButton` → coord tap on the call button's location succeeds.
- **Cancel mid-wizard** at each macro boundary; verify existing recipe (if any) is unaffected.
- **Undo across macro boundary** (step 3 → undo → back to digit 10/10).
- **Leave-and-return** (press Home during RECORD_DIGITS, wait 10 s, return to BizPhone; wizard resumes).
- **Duplicate warning path** (BizPhone, airplane mode OFF); verify message and re-record flow.
- **Accessibility service disabled** mid-wizard; verify graceful cancellation.

No instrumentation tests — overlay + accessibility integration is too device-dependent to stabilize in CI. Manual matrix covers it.
