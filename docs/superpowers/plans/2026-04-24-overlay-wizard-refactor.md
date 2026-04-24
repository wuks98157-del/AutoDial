# Overlay Wizard Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the screen-switching wizard with an overlay-driven flow; user stays in target app throughout. Also unblock Mobile VOIP setup by reusing `PRESS_CALL`'s step data for runtime hang-up.

**Architecture:** Pure `WizardStateMachine` owned by `AutoDialAccessibilityService`, which also hosts a new `WizardOverlayController` that renders a Compose card via `WindowManager.TYPE_APPLICATION_OVERLAY`. Dialer launches wizard directly into the target app, no intermediate screen. `WizardScreen`/`WizardViewModel` are deleted. `TargetApps.autoHangupResourceId` becomes `TargetApps.hangupStrategy` (sealed) with BizPhone=AutoHangupById, MobileVOIP=ReuseCallButton.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Android AccessibilityService, Room, kotlinx.coroutines + Flow.

**Spec:** `docs/superpowers/specs/2026-04-24-overlay-wizard-refactor-design.md`

---

## Task 1: HangupStrategy refactor + runtime dispatch

**Files:**
- Modify: `app/src/main/java/com/autodial/model/TargetApp.kt`
- Modify: `app/src/main/java/com/autodial/service/RunForegroundService.kt:182-213` (`RunState.InCall` branch)

- [ ] **Step 1.1: Replace `autoHangupResourceId` with sealed `HangupStrategy` in `TargetApp.kt`**

Replace the entire content of `TargetApp.kt` with:

```kotlin
package com.autodial.model

object TargetApps {
    const val BIZPHONE = "com.b3networks.bizphone"
    const val MOBILE_VOIP = "finarea.MobileVoip"

    fun retainsNumberAfterHangup(targetPackage: String): Boolean =
        targetPackage == BIZPHONE

    fun clearDigitsLongPressMs(targetPackage: String): Long? = when (targetPackage) {
        BIZPHONE -> 2500L
        else -> null
    }

    // How the runtime should end a call for this target.
    sealed class HangupStrategy {
        // Tap the visible+clickable node with this resourceId (BizPhone).
        data class AutoHangupById(val resourceId: String) : HangupStrategy()
        // Re-execute the recorded PRESS_CALL step — the end-call button sits
        // at the same on-screen location as the call button (Mobile VOIP).
        object ReuseCallButton : HangupStrategy()
        // Default — execute the recorded HANG_UP step.
        object RecordedStep : HangupStrategy()
    }

    fun hangupStrategy(targetPackage: String): HangupStrategy = when (targetPackage) {
        BIZPHONE    -> HangupStrategy.AutoHangupById("com.b3networks.bizphone:id/clearCallButton")
        MOBILE_VOIP -> HangupStrategy.ReuseCallButton
        else        -> HangupStrategy.RecordedStep
    }
}
```

- [ ] **Step 1.2: Update `RunForegroundService.kt` — replace the InCall hangup dispatch**

Find the block at roughly lines 194–213 that currently reads:

```kotlin
val autoId = TargetApps.autoHangupResourceId(state.params.targetPackage)
if (autoId != null) {
    autoHangup(autoId, accessService)
} else {
    executeStep("HANG_UP", state.params, accessService)
}
```

Replace with:

```kotlin
when (val strategy = TargetApps.hangupStrategy(state.params.targetPackage)) {
    is TargetApps.HangupStrategy.AutoHangupById ->
        autoHangup(strategy.resourceId, accessService)
    TargetApps.HangupStrategy.ReuseCallButton ->
        // Mobile VOIP: the end-call view is different from the dial-pad call
        // button but sits at the same location and size, so replaying the
        // recorded PRESS_CALL step (Tier-3 coord fallback if needed) reaches
        // the hangup view during an active call. History still records this
        // as HANG_UP for diagnostics.
        executeStep("PRESS_CALL", state.params, accessService, logicalStepId = "HANG_UP")
    TargetApps.HangupStrategy.RecordedStep ->
        executeStep("HANG_UP", state.params, accessService)
}
```

- [ ] **Step 1.3: Add `logicalStepId` parameter to `executeStep` in `RunForegroundService.kt`**

Find the existing `executeStep` function (around line 207) and change the signature + log/history writes to accept an optional `logicalStepId`:

```kotlin
private suspend fun executeStep(
    stepId: String,
    params: RunParams,
    accessService: AutoDialAccessibilityService,
    logicalStepId: String = stepId
) {
    val steps = recipeRepo.getSteps(params.targetPackage)
    val step = steps.firstOrNull { it.stepId == stepId }
    if (step == null) {
        Log.w(TAG, "step $stepId NOT in recipe for ${params.targetPackage}")
        stateMachine.onEvent(RunEvent.StepActionFailed(logicalStepId, "failed:step-not-recorded"))
        return
    }
    Log.i(TAG, "EXEC $logicalStepId (data=$stepId) rid=${step.resourceId} ...")
    val outcome = accessService.executeStep(step)
    val outStr = when (outcome) {
        is StepOutcome.Ok -> outcome.outcome
        is StepOutcome.Failed -> outcome.reason
    }
    Log.i(TAG, "EXEC $logicalStepId result=$outStr")
    historyRepo.logStepEvent(RunStepEvent(
        runId = runId, cycleIndex = currentCycle(), stepId = logicalStepId,
        at = System.currentTimeMillis(), outcome = outStr, detail = null
    ))
    when (outcome) {
        is StepOutcome.Ok -> stateMachine.onEvent(RunEvent.StepActionSucceeded(logicalStepId, outStr))
        is StepOutcome.Failed -> stateMachine.onEvent(RunEvent.StepActionFailed(logicalStepId, outStr))
    }
}
```

- [ ] **Step 1.4: Build, verify no references to `autoHangupResourceId` remain**

Run from Android Studio: Build → Rebuild Project. Fix any unresolved-reference errors (there should be none — only `RunForegroundService` called it).

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/java/com/autodial/model/TargetApp.kt \
        app/src/main/java/com/autodial/service/RunForegroundService.kt
git commit -m "refactor: HangupStrategy sealed class for per-app hangup dispatch"
```

---

## Task 2: WizardState + WizardCommand + WizardEvent + WizardSideEffect (pure types)

**Files:**
- Create: `app/src/main/java/com/autodial/wizard/WizardState.kt`

- [ ] **Step 2.1: Create `WizardState.kt` with all sealed types**

```kotlin
package com.autodial.wizard

import com.autodial.model.RecordedStep

sealed class WizardState {
    object Idle : WizardState()

    data class Step(
        val targetPackage: String,
        val macro: MacroStep,
        val queue: List<String>,
        val captured: Map<String, RecordedStep>,
        val undoStack: List<String>,
        val lastCapture: RecordedStep?
    ) : WizardState()

    data class AwaitingReturn(val resume: Step) : WizardState()

    data class DuplicateWarning(
        val stepId: String,
        val message: String,
        val resume: Step
    ) : WizardState()

    data class Completed(val recipeSaved: Boolean?, val error: String? = null) : WizardState()

    object Cancelled : WizardState()
}

enum class MacroStep { OPEN_DIAL_PAD, RECORD_DIGITS, CLEAR_DIGITS, PRESS_CALL }

sealed class WizardCommand {
    data class Start(val targetPackage: String) : WizardCommand()
    object Cancel : WizardCommand()
    object Undo : WizardCommand()
    object ReRecord : WizardCommand()
}

sealed class WizardEvent {
    data class Captured(val step: RecordedStep) : WizardEvent()
    object TargetBackgrounded : WizardEvent()
    object TargetForegrounded : WizardEvent()
    object ServiceRevoked : WizardEvent()
    data class RecipeSaveResult(val success: Boolean, val error: String? = null) : WizardEvent()
}

sealed class WizardSideEffect {
    data class AutoWipeDialPad(val clearStep: RecordedStep, val targetPackage: String) : WizardSideEffect()
    data class SaveRecipe(val targetPackage: String, val captured: Map<String, RecordedStep>) : WizardSideEffect()
}
```

- [ ] **Step 2.2: Commit**

```bash
git add app/src/main/java/com/autodial/wizard/WizardState.kt
git commit -m "feat(wizard): WizardState + Command/Event/SideEffect sealed types"
```

---

## Task 3: WizardStateMachine (pure logic + tests, TDD)

**Files:**
- Create: `app/src/main/java/com/autodial/wizard/WizardStateMachine.kt`
- Create: `app/src/test/java/com/autodial/wizard/WizardStateMachineTest.kt`

- [ ] **Step 3.1: Create skeleton `WizardStateMachine.kt`**

```kotlin
package com.autodial.wizard

import com.autodial.model.RecordedStep
import kotlinx.coroutines.flow.*

class WizardStateMachine {

    private val _state = MutableStateFlow<WizardState>(WizardState.Idle)
    val state: StateFlow<WizardState> = _state.asStateFlow()

    private val _sideEffects = MutableSharedFlow<WizardSideEffect>(extraBufferCapacity = 8)
    val sideEffects: SharedFlow<WizardSideEffect> = _sideEffects.asSharedFlow()

    fun onCommand(cmd: WizardCommand) {
        when (cmd) {
            is WizardCommand.Start -> handleStart(cmd.targetPackage)
            WizardCommand.Cancel -> handleCancel()
            WizardCommand.Undo -> handleUndo()
            WizardCommand.ReRecord -> handleReRecord()
        }
    }

    fun onEvent(event: WizardEvent) {
        when (event) {
            is WizardEvent.Captured -> handleCaptured(event.step)
            WizardEvent.TargetBackgrounded -> handleBackgrounded()
            WizardEvent.TargetForegrounded -> handleForegrounded()
            WizardEvent.ServiceRevoked -> handleRevoked()
            is WizardEvent.RecipeSaveResult -> handleSaveResult(event.success, event.error)
        }
    }

    private fun queueFor(macro: MacroStep): List<String> = when (macro) {
        MacroStep.OPEN_DIAL_PAD -> listOf("OPEN_DIAL_PAD")
        MacroStep.RECORD_DIGITS -> (0..9).map { "DIGIT_$it" }
        MacroStep.CLEAR_DIGITS -> listOf("CLEAR_DIGITS")
        MacroStep.PRESS_CALL -> listOf("PRESS_CALL")
    }

    private fun nextMacro(cur: MacroStep): MacroStep? = when (cur) {
        MacroStep.OPEN_DIAL_PAD -> MacroStep.RECORD_DIGITS
        MacroStep.RECORD_DIGITS -> MacroStep.CLEAR_DIGITS
        MacroStep.CLEAR_DIGITS -> MacroStep.PRESS_CALL
        MacroStep.PRESS_CALL -> null
    }

    private fun macroOf(stepId: String): MacroStep = when {
        stepId == "OPEN_DIAL_PAD" -> MacroStep.OPEN_DIAL_PAD
        stepId.startsWith("DIGIT_") -> MacroStep.RECORD_DIGITS
        stepId == "CLEAR_DIGITS" -> MacroStep.CLEAR_DIGITS
        stepId == "PRESS_CALL" -> MacroStep.PRESS_CALL
        else -> throw IllegalArgumentException("unknown stepId: $stepId")
    }

    private fun handleStart(pkg: String) {
        if (_state.value !is WizardState.Idle) return
        _state.value = WizardState.Step(
            targetPackage = pkg,
            macro = MacroStep.OPEN_DIAL_PAD,
            queue = queueFor(MacroStep.OPEN_DIAL_PAD),
            captured = emptyMap(),
            undoStack = emptyList(),
            lastCapture = null
        )
    }

    private fun handleCancel() {
        val s = _state.value
        if (s is WizardState.Completed || s is WizardState.Cancelled || s is WizardState.Idle) return
        _state.value = WizardState.Cancelled
    }

    private fun handleCaptured(step: RecordedStep) {
        val s = _state.value as? WizardState.Step ?: return
        // Ignore captures not in the expected queue.
        if (step.stepId !in s.queue) return

        val newCaptured = s.captured + (step.stepId to step)
        val newQueue = s.queue - step.stepId
        val newUndo = s.undoStack + step.stepId

        if (newQueue.isNotEmpty()) {
            // Stay in same macro with shrunken queue.
            _state.value = s.copy(
                queue = newQueue, captured = newCaptured,
                undoStack = newUndo, lastCapture = step
            )
            return
        }
        // Macro complete — advance or finish.
        val next = nextMacro(s.macro)
        if (next == null) {
            // PRESS_CALL completed. Check for duplicate.
            val dup = findDuplicateWarning(step, newCaptured)
            if (dup != null) {
                _state.value = WizardState.DuplicateWarning(
                    stepId = step.stepId, message = dup,
                    resume = s.copy(
                        queue = listOf("PRESS_CALL"),
                        captured = newCaptured - "PRESS_CALL",
                        undoStack = s.undoStack,
                        lastCapture = null
                    )
                )
                return
            }
            // All good — emit SaveRecipe side-effect and enter Completed(null).
            _sideEffects.tryEmit(WizardSideEffect.SaveRecipe(s.targetPackage, newCaptured))
            _state.value = WizardState.Completed(recipeSaved = null)
            return
        }
        // Macro advance.
        val advanced = s.copy(
            macro = next, queue = queueFor(next),
            captured = newCaptured, undoStack = newUndo, lastCapture = step
        )
        _state.value = advanced
        // Emit autowipe side-effect on CLEAR_DIGITS → PRESS_CALL transition.
        if (s.macro == MacroStep.CLEAR_DIGITS && next == MacroStep.PRESS_CALL) {
            val clear = newCaptured["CLEAR_DIGITS"] ?: return
            _sideEffects.tryEmit(WizardSideEffect.AutoWipeDialPad(clear, s.targetPackage))
        }
    }

    private fun handleUndo() {
        val s = _state.value as? WizardState.Step ?: return
        if (s.undoStack.isEmpty()) return
        val lastId = s.undoStack.last()
        val newUndo = s.undoStack.dropLast(1)
        val newCaptured = s.captured - lastId
        // Recompute macro from the last remaining captured stepId (or OPEN_DIAL_PAD).
        val macro = if (newUndo.isEmpty()) MacroStep.OPEN_DIAL_PAD
                    else macroOf(newUndo.last())
        // Rebuild queue: full queue for macro minus already-captured stepIds in that macro.
        val fullQueue = queueFor(macro)
        val capturedInMacro = newCaptured.keys.filter { macroOf(it) == macro }
        val newQueue = fullQueue - capturedInMacro.toSet()
        _state.value = s.copy(
            macro = macro, queue = newQueue,
            captured = newCaptured, undoStack = newUndo,
            lastCapture = newCaptured[newUndo.lastOrNull()]
        )
    }

    private fun handleReRecord() {
        val s = _state.value as? WizardState.DuplicateWarning ?: return
        _state.value = s.resume
    }

    private fun handleBackgrounded() {
        val s = _state.value as? WizardState.Step ?: return
        _state.value = WizardState.AwaitingReturn(resume = s)
    }

    private fun handleForegrounded() {
        val s = _state.value as? WizardState.AwaitingReturn ?: return
        _state.value = s.resume
    }

    private fun handleRevoked() {
        val s = _state.value
        if (s is WizardState.Completed || s is WizardState.Cancelled || s is WizardState.Idle) return
        _state.value = WizardState.Cancelled
    }

    private fun handleSaveResult(success: Boolean, error: String?) {
        val s = _state.value as? WizardState.Completed ?: return
        if (s.recipeSaved != null) return
        _state.value = s.copy(recipeSaved = success, error = error)
    }

    // Detect "same-button-captured-twice" — primary case is BizPhone's call-start
    // being captured as the in-call end button due to fast transition.
    private fun findDuplicateWarning(
        step: RecordedStep,
        captured: Map<String, RecordedStep>
    ): String? {
        val rid = step.resourceId ?: return null
        val match = captured.values.firstOrNull { prior ->
            prior.stepId != step.stepId &&
            prior.resourceId == rid &&
            kotlin.math.abs(prior.boundsRelX - step.boundsRelX) < 0.02f &&
            kotlin.math.abs(prior.boundsRelY - step.boundsRelY) < 0.02f
        } ?: return null
        return "This capture looks identical to ${match.stepId} (same id, same position). " +
               "On BizPhone this usually means the call button tap was too fast and we " +
               "caught the in-call end button instead. Re-record with AIRPLANE MODE ON " +
               "so BizPhone can't transition away mid-tap."
    }
}
```

- [ ] **Step 3.2: Write `WizardStateMachineTest.kt` — happy path transitions**

```kotlin
package com.autodial.wizard

import com.autodial.model.RecordedStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WizardStateMachineTest {

    private lateinit var sm: WizardStateMachine
    private val pkg = "com.b3networks.bizphone"

    @Before fun setup() { sm = WizardStateMachine() }

    private fun step(id: String, rid: String? = "com.test:id/$id",
                     relX: Float = 0.1f, relY: Float = 0.1f) = RecordedStep(
        stepId = id, resourceId = rid, text = null, className = null,
        boundsRelX = relX, boundsRelY = relY, boundsRelW = 0.2f, boundsRelH = 0.1f,
        screenshotHashHex = null, recordedOnDensityDpi = 320,
        recordedOnScreenW = 1080, recordedOnScreenH = 2400,
        missingResourceId = rid == null, digitAutoDetected = false
    )

    @Test fun startTransitionsFromIdleToOpenDialPad() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        val s = sm.state.value
        assertTrue(s is WizardState.Step)
        s as WizardState.Step
        assertEquals(MacroStep.OPEN_DIAL_PAD, s.macro)
        assertEquals(listOf("OPEN_DIAL_PAD"), s.queue)
    }

    @Test fun openDialPadCaptureAdvancesToRecordDigits() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.RECORD_DIGITS, s.macro)
        assertEquals((0..9).map { "DIGIT_$it" }, s.queue)
    }

    @Test fun digitCapturesShrinkQueueInOrder() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        sm.onEvent(WizardEvent.Captured(step("DIGIT_0")))
        sm.onEvent(WizardEvent.Captured(step("DIGIT_1")))
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.RECORD_DIGITS, s.macro)
        assertEquals((2..9).map { "DIGIT_$it" }, s.queue)
    }

    @Test fun allDigitsCapturedAdvancesToClearDigits() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.CLEAR_DIGITS, s.macro)
    }

    @Test fun pressCallCapturedEmitsSaveRecipeAndTransitionsToCompletedSaving() = runTest {
        val effects = mutableListOf<WizardSideEffect>()
        val job = kotlinx.coroutines.GlobalScope.launch {
            sm.sideEffects.collect { effects.add(it) }
        }
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        job.cancel()
        val s = sm.state.value as WizardState.Completed
        assertNull(s.recipeSaved) // transient saving
        assertTrue(effects.any { it is WizardSideEffect.SaveRecipe })
    }

    @Test fun recipeSaveResultSuccessUpdatesCompleted() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        sm.onEvent(WizardEvent.RecipeSaveResult(success = true))
        val s = sm.state.value as WizardState.Completed
        assertEquals(true, s.recipeSaved)
        assertNull(s.error)
    }

    @Test fun recipeSaveResultFailureStoresError() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        sm.onEvent(WizardEvent.RecipeSaveResult(success = false, error = "disk full"))
        val s = sm.state.value as WizardState.Completed
        assertEquals(false, s.recipeSaved)
        assertEquals("disk full", s.error)
    }

    @Test fun clearToPressCallTransitionEmitsAutoWipe() = runTest {
        val effects = mutableListOf<WizardSideEffect>()
        val job = kotlinx.coroutines.GlobalScope.launch {
            sm.sideEffects.collect { effects.add(it) }
        }
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        job.cancel()
        assertTrue(effects.any { it is WizardSideEffect.AutoWipeDialPad })
    }

    @Test fun cancelFromActiveStateTransitionsToCancelled() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onCommand(WizardCommand.Cancel)
        assertEquals(WizardState.Cancelled, sm.state.value)
    }

    @Test fun undoPopsLastCaptureInSameMacro() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        sm.onEvent(WizardEvent.Captured(step("DIGIT_0")))
        sm.onEvent(WizardEvent.Captured(step("DIGIT_1")))
        sm.onCommand(WizardCommand.Undo)
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.RECORD_DIGITS, s.macro)
        assertEquals((1..9).map { "DIGIT_$it" }, s.queue)
        assertFalse(s.captured.containsKey("DIGIT_1"))
        assertTrue(s.captured.containsKey("DIGIT_0"))
    }

    @Test fun undoCrossesMacroBoundary() = runTest {
        // Capture through CLEAR_DIGITS, then undo once.
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        // Now at PRESS_CALL. Undo.
        sm.onCommand(WizardCommand.Undo)
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.CLEAR_DIGITS, s.macro)
        assertEquals(listOf("CLEAR_DIGITS"), s.queue)
        assertFalse(s.captured.containsKey("CLEAR_DIGITS"))
    }

    @Test fun targetBackgroundedTransitionsToAwaitingReturn() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        sm.onEvent(WizardEvent.TargetBackgrounded)
        val s = sm.state.value
        assertTrue(s is WizardState.AwaitingReturn)
    }

    @Test fun targetForegroundedResumesPriorStep() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        sm.onEvent(WizardEvent.Captured(step("DIGIT_0")))
        sm.onEvent(WizardEvent.TargetBackgrounded)
        sm.onEvent(WizardEvent.TargetForegrounded)
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.RECORD_DIGITS, s.macro)
        assertEquals((1..9).map { "DIGIT_$it" }, s.queue)
    }

    @Test fun duplicatePressCallTriggersDuplicateWarning() = runTest {
        // Simulate PRESS_CALL being captured with same rid/bounds as CLEAR_DIGITS.
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "shared", relX = 0.5f, relY = 0.5f)))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "shared", relX = 0.5f, relY = 0.5f)))
        val s = sm.state.value
        assertTrue("expected DuplicateWarning, got $s", s is WizardState.DuplicateWarning)
    }

    @Test fun reRecordFromDuplicateRestoresStepWithoutPressCall() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "shared", relX = 0.5f, relY = 0.5f)))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "shared", relX = 0.5f, relY = 0.5f)))
        sm.onCommand(WizardCommand.ReRecord)
        val s = sm.state.value as WizardState.Step
        assertEquals(MacroStep.PRESS_CALL, s.macro)
        assertEquals(listOf("PRESS_CALL"), s.queue)
        assertFalse(s.captured.containsKey("PRESS_CALL"))
    }

    @Test fun serviceRevokedDuringWizardTransitionsToCancelled() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.ServiceRevoked)
        assertEquals(WizardState.Cancelled, sm.state.value)
    }
}
```

- [ ] **Step 3.3: Run tests**

Open `WizardStateMachineTest` in Android Studio and run. All tests should pass. Fix any compile errors in the state machine.

- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/java/com/autodial/wizard/WizardStateMachine.kt \
        app/src/test/java/com/autodial/wizard/WizardStateMachineTest.kt
git commit -m "feat(wizard): WizardStateMachine with pure-logic tests"
```

---

## Task 4: WizardRecipeWriter

**Files:**
- Create: `app/src/main/java/com/autodial/wizard/WizardRecipeWriter.kt`

- [ ] **Step 4.1: Create `WizardRecipeWriter.kt`**

```kotlin
package com.autodial.wizard

import android.content.Context
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.RecordedStep
import com.autodial.model.TargetApps

class WizardRecipeWriter(
    private val context: Context,
    private val recipeRepo: RecipeRepository
) {
    suspend fun save(targetPackage: String, captured: Map<String, RecordedStep>) {
        val displayName = when (targetPackage) {
            TargetApps.BIZPHONE -> "BizPhone"
            TargetApps.MOBILE_VOIP -> "Mobile VOIP"
            else -> targetPackage
        }
        val installedVersion = try {
            context.packageManager.getPackageInfo(targetPackage, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        val recipe = Recipe(
            targetPackage = targetPackage,
            displayName = displayName,
            recordedVersion = installedVersion,
            recordedAt = System.currentTimeMillis(),
            schemaVersion = 1
        )
        val steps = captured.values.map { r ->
            RecipeStep(
                targetPackage = targetPackage,
                stepId = r.stepId,
                resourceId = r.resourceId,
                text = r.text,
                className = r.className,
                boundsRelX = r.boundsRelX, boundsRelY = r.boundsRelY,
                boundsRelW = r.boundsRelW, boundsRelH = r.boundsRelH,
                screenshotHashHex = r.screenshotHashHex,
                recordedOnDensityDpi = r.recordedOnDensityDpi,
                recordedOnScreenW = r.recordedOnScreenW,
                recordedOnScreenH = r.recordedOnScreenH
            )
        }
        recipeRepo.saveRecipe(recipe, steps)
    }
}
```

- [ ] **Step 4.2: Commit**

```bash
git add app/src/main/java/com/autodial/wizard/WizardRecipeWriter.kt
git commit -m "feat(wizard): WizardRecipeWriter helper"
```

---

## Task 5: WizardCard composable

**Files:**
- Create: `app/src/main/java/com/autodial/overlay/WizardCard.kt`

- [ ] **Step 5.1: Create `WizardCard.kt`**

```kotlin
package com.autodial.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodial.wizard.MacroStep
import com.autodial.wizard.WizardState

private val AdOrange = Color(0xFFFF6B00)
private val AdBg = Color(0xCC0D0D0D)
private val AdBorder = Color(0xFF2A2A2A)
private val AdText = Color.White
private val AdTextDim = Color(0xFFAAAAAA)
private val AdGreen = Color(0xFF34C759)
private val AdRed = Color(0xFFE53935)

@Composable
fun WizardCard(
    state: WizardState,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onReRecord: () -> Unit,
    onRetrySave: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    Box(
        Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AdBg)
            .border(1.dp, AdBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, d -> onDrag(d.x, d.y) }
            }
    ) {
        when (state) {
            is WizardState.Step -> StepBody(state, onUndo, onCancel)
            is WizardState.AwaitingReturn -> AwaitingBody(state, onCancel)
            is WizardState.DuplicateWarning -> DuplicateBody(state, onReRecord, onCancel)
            is WizardState.Completed -> CompletedBody(state, onRetrySave, onCancel)
            WizardState.Cancelled -> Text("Cancelled", color = AdText)
            WizardState.Idle -> {}
        }
    }
}

@Composable
private fun StepBody(state: WizardState.Step, onUndo: () -> Unit, onCancel: () -> Unit) {
    val macroIndex = state.macro.ordinal + 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "STEP $macroIndex / 4",
                color = AdOrange, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
            Text("✕", color = AdTextDim, fontSize = 14.sp,
                modifier = Modifier
                    .size(24.dp)
                    .pointerInput(Unit) { detectTapGestures(onCancel) }
            )
        }
        Text(
            text = promptFor(state),
            color = AdText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        if (state.macro == MacroStep.RECORD_DIGITS) {
            DigitChips(state.captured.keys)
        }
        if (state.lastCapture != null) {
            Text("✓ ${state.lastCapture.stepId}", color = AdGreen, fontSize = 10.sp)
        }
        if (state.undoStack.isNotEmpty()) {
            Button(
                onClick = onUndo,
                colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("↶ Undo", color = AdText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DigitChips(captured: Set<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (0..9).forEach { d ->
            val done = "DIGIT_$d" in captured
            Text(
                text = "$d",
                color = if (done) AdGreen else AdTextDim,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .size(width = 16.dp, height = 16.dp)
                    .background(
                        if (done) AdGreen.copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(3.dp)
                    ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun AwaitingBody(state: WizardState.AwaitingReturn, onCancel: () -> Unit) {
    Column {
        Text("Return to target app",
            color = AdOrange, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text("The wizard will resume when you come back.",
            color = AdText, fontSize = 11.sp)
        Text("✕ Cancel", color = AdTextDim, fontSize = 10.sp,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onCancel) })
    }
}

@Composable
private fun DuplicateBody(
    state: WizardState.DuplicateWarning,
    onReRecord: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠ Duplicate capture",
            color = AdRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text(state.message, color = AdText, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onReRecord,
                colors = ButtonDefaults.buttonColors(containerColor = AdOrange),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)) {
                Text("Re-record", color = Color.Black, fontSize = 11.sp)
            }
            Button(onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)) {
                Text("Cancel", color = AdText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CompletedBody(
    state: WizardState.Completed,
    onRetrySave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state.recipeSaved) {
            null -> Text("Saving…", color = AdOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            true -> Text("✓ Recipe saved", color = AdGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            false -> {
                Text("✕ Save failed", color = AdRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (state.error != null) Text(state.error, color = AdTextDim, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onRetrySave,
                        colors = ButtonDefaults.buttonColors(containerColor = AdOrange),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(26.dp)) {
                        Text("Retry", color = Color.Black, fontSize = 11.sp)
                    }
                    Button(onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(26.dp)) {
                        Text("Dismiss", color = AdText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun promptFor(state: WizardState.Step): String = when (state.macro) {
    MacroStep.OPEN_DIAL_PAD -> "Tap the dial pad / keypad icon."
    MacroStep.RECORD_DIGITS -> "Tap digits 0, 1, 2, … 9 in order."
    MacroStep.CLEAR_DIGITS -> "Tap backspace / clear once. AutoDial will auto-wipe after."
    MacroStep.PRESS_CALL -> "Turn on AIRPLANE MODE first, then tap the call bar."
}

// Local import — detectTapGestures from compose.foundation.gestures
private val detectTapGestures: suspend androidx.compose.ui.input.pointer.PointerInputScope.(() -> Unit) -> Unit =
    { onTap ->
        androidx.compose.foundation.gestures.detectTapGestures(onTap = { onTap() })
    }
```

- [ ] **Step 5.2: Fix the `detectTapGestures` import** (the inline assignment above is a hack; replace with a direct import)

Remove the bottom `private val detectTapGestures = ...` block. At the top of the file add:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
```

Change all call sites from `.pointerInput(Unit) { detectTapGestures(onCancel) }` to:

```kotlin
.pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
```

- [ ] **Step 5.3: Build** (Android Studio: Build → Rebuild Project). Fix any import errors.

- [ ] **Step 5.4: Commit**

```bash
git add app/src/main/java/com/autodial/overlay/WizardCard.kt
git commit -m "feat(wizard): WizardCard composable for overlay"
```

---

## Task 6: WizardOverlayController

**Files:**
- Create: `app/src/main/java/com/autodial/overlay/WizardOverlayController.kt`

- [ ] **Step 6.1: Create `WizardOverlayController.kt`**

Mirror the structure of the existing `OverlayController.kt` but render `WizardCard` instead of the run bubble. Use the same `SimpleLifecycleOwner` pattern and `TYPE_APPLICATION_OVERLAY` flags.

```kotlin
package com.autodial.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autodial.wizard.WizardState

class WizardOverlayController(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: SimpleLifecycleOwner? = null

    private var overlayX = 40
    private var overlayY = 140

    private val _state = mutableStateOf<WizardState>(WizardState.Idle)

    private var onUndo: () -> Unit = {}
    private var onCancel: () -> Unit = {}
    private var onReRecord: () -> Unit = {}
    private var onRetrySave: () -> Unit = {}

    fun show(
        onUndo: () -> Unit,
        onCancel: () -> Unit,
        onReRecord: () -> Unit,
        onRetrySave: () -> Unit
    ) {
        if (overlayView != null) return
        this.onUndo = onUndo
        this.onCancel = onCancel
        this.onReRecord = onReRecord
        this.onRetrySave = onRetrySave

        val owner = SimpleLifecycleOwner().also { it.start() }
        lifecycleOwner = owner

        val view = ComposeView(context)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        view.setContent {
            WizardCard(
                state = _state.value,
                onUndo = { this.onUndo() },
                onCancel = { this.onCancel() },
                onReRecord = { this.onReRecord() },
                onRetrySave = { this.onRetrySave() },
                onDrag = { dx, dy ->
                    overlayX += dx.toInt()
                    overlayY += dy.toInt()
                    updatePosition()
                }
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = overlayX; y = overlayY
        }
        wm.addView(view, params)
        overlayView = view
    }

    fun updateState(state: WizardState) {
        _state.value = state
    }

    fun dismiss() {
        val v = overlayView ?: return
        overlayView = null
        try { wm.removeView(v) } catch (_: IllegalArgumentException) {}
        lifecycleOwner?.stop()
        lifecycleOwner = null
    }

    private fun updatePosition() {
        val v = overlayView ?: return
        val p = v.layoutParams as WindowManager.LayoutParams
        p.x = overlayX; p.y = overlayY
        wm.updateViewLayout(v, p)
    }

    private inner class SimpleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        fun start() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            if (registry.currentState == Lifecycle.State.INITIALIZED) return
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
```

- [ ] **Step 6.2: Build**, fix any import errors.

- [ ] **Step 6.3: Commit**

```bash
git add app/src/main/java/com/autodial/overlay/WizardOverlayController.kt
git commit -m "feat(wizard): WizardOverlayController renders WizardCard"
```

---

## Task 7: AutoDialAccessibilityService — beginWizard / endWizard + side-effect handling

**Files:**
- Modify: `app/src/main/java/com/autodial/accessibility/AutoDialAccessibilityService.kt`

- [ ] **Step 7.1: Add `@AndroidEntryPoint` and inject `RecipeRepository`**

At top of file:

```kotlin
import com.autodial.data.repository.RecipeRepository
import com.autodial.overlay.WizardOverlayController
import com.autodial.wizard.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
```

On the class:

```kotlin
@AndroidEntryPoint
class AutoDialAccessibilityService : AccessibilityService() {
    @Inject lateinit var recipeRepo: RecipeRepository
    // ...
}
```

- [ ] **Step 7.2: Add wizard state members**

Inside the class, alongside existing `recorder` / `player`:

```kotlin
private var wizardStateMachine: WizardStateMachine? = null
private var wizardOverlay: WizardOverlayController? = null
private var recipeWriter: WizardRecipeWriter? = null
private var wizardActivePackage: String? = null
```

- [ ] **Step 7.3: Add `beginWizard` / `endWizard` methods**

```kotlin
fun beginWizard(targetPackage: String) {
    if (wizardStateMachine != null) return
    val sm = WizardStateMachine()
    val overlay = WizardOverlayController(this)
    val writer = WizardRecipeWriter(applicationContext, recipeRepo)
    wizardStateMachine = sm
    wizardOverlay = overlay
    recipeWriter = writer
    wizardActivePackage = targetPackage

    // Launch target app.
    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launchIntent != null) applicationContext.startActivity(launchIntent)

    overlay.show(
        onUndo = { sm.onCommand(WizardCommand.Undo) },
        onCancel = { sm.onCommand(WizardCommand.Cancel) },
        onReRecord = { sm.onCommand(WizardCommand.ReRecord) },
        onRetrySave = {
            val state = sm.state.value as? WizardState.Completed ?: return@show
            // The captured map lives in the last Step state; re-trigger save via side-effect.
            // Simplest: collect the last Step snapshot when transitioning to Completed.
            // Handled by the side-effect subscription below (Retry fires a new SaveRecipe event).
            scope.launch { triggerLastKnownSave(targetPackage) }
        }
    )
    sm.onCommand(WizardCommand.Start(targetPackage))

    // Collect captures from UiRecorder → state machine.
    scope.launch {
        recorder?.capturedSteps?.collect { recorded ->
            sm.onEvent(WizardEvent.Captured(recorded))
        }
    }
    // Re-arm UiRecorder whenever the current Step's queue changes.
    scope.launch {
        sm.state.collect { state ->
            overlay.updateState(state)
            when (state) {
                is WizardState.Step -> recorder?.startCapturing(state.queue, state.targetPackage)
                is WizardState.AwaitingReturn -> recorder?.stopCapturing()
                WizardState.Cancelled, is WizardState.Completed -> {
                    if (state is WizardState.Completed && state.recipeSaved == true) {
                        kotlinx.coroutines.delay(2000L)
                    }
                    endWizard()
                }
                else -> {}
            }
        }
    }
    // Handle side-effects.
    scope.launch {
        sm.sideEffects.collect { effect ->
            when (effect) {
                is WizardSideEffect.AutoWipeDialPad ->
                    autoWipeDialPad(effect.clearStep, effect.targetPackage)
                is WizardSideEffect.SaveRecipe ->
                    runSave(writer, effect.targetPackage, effect.captured, sm)
            }
        }
    }
}

fun endWizard() {
    wizardOverlay?.dismiss()
    wizardOverlay = null
    wizardStateMachine = null
    recipeWriter = null
    wizardActivePackage = null
    recorder?.stopCapturing()
}

private suspend fun runSave(
    writer: WizardRecipeWriter,
    targetPackage: String,
    captured: Map<String, com.autodial.model.RecordedStep>,
    sm: WizardStateMachine
) {
    try {
        writer.save(targetPackage, captured)
        sm.onEvent(WizardEvent.RecipeSaveResult(success = true))
    } catch (e: Exception) {
        sm.onEvent(WizardEvent.RecipeSaveResult(success = false, error = e.message))
    }
}

// Taps the recorded CLEAR_DIGITS node ~15× to empty the dial pad. Same logic
// as the old WizardViewModel.autoWipeDialPad. Uses the UiPlayer via executeStep.
private fun autoWipeDialPad(
    clear: com.autodial.model.RecordedStep,
    targetPackage: String
) {
    val step = com.autodial.data.db.entity.RecipeStep(
        targetPackage = targetPackage, stepId = "CLEAR_DIGITS",
        resourceId = clear.resourceId, text = clear.text, className = clear.className,
        boundsRelX = clear.boundsRelX, boundsRelY = clear.boundsRelY,
        boundsRelW = clear.boundsRelW, boundsRelH = clear.boundsRelH,
        screenshotHashHex = clear.screenshotHashHex,
        recordedOnDensityDpi = clear.recordedOnDensityDpi,
        recordedOnScreenW = clear.recordedOnScreenW,
        recordedOnScreenH = clear.recordedOnScreenH
    )
    scope.launch {
        // Bring target app to front so the wipe taps land there, not on AutoDial.
        val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) applicationContext.startActivity(intent)
        kotlinx.coroutines.delay(500L)
        repeat(15) {
            executeStep(step)
            kotlinx.coroutines.delay(120L)
        }
    }
}

// For onRetrySave: we need the last captured map. The SideEffect queue already
// emitted SaveRecipe once; retry re-fires the side-effect with the same data.
private suspend fun triggerLastKnownSave(targetPackage: String) {
    // Walk the state tree to find the last captured map. In practice the
    // Completed state carries nothing, so we use the last Step observed before
    // completion. Simplest implementation: keep a snapshot at the SaveRecipe
    // side-effect arrival.
    val snap = lastCapturedForSave ?: return
    val writer = recipeWriter ?: return
    val sm = wizardStateMachine ?: return
    // Reset Completed to transient state and re-save.
    runSave(writer, targetPackage, snap, sm)
}

@Volatile private var lastCapturedForSave: Map<String, com.autodial.model.RecordedStep>? = null
```

- [ ] **Step 7.4: Update the `SaveRecipe` side-effect branch to record `lastCapturedForSave`**

Change the `runSave` collection branch inside `beginWizard` to capture the map:

```kotlin
is WizardSideEffect.SaveRecipe -> {
    lastCapturedForSave = effect.captured
    runSave(writer, effect.targetPackage, effect.captured, sm)
}
```

- [ ] **Step 7.5: Hook `TargetBackgrounded` / `TargetForegrounded` in `onAccessibilityEvent`**

Modify the existing `onAccessibilityEvent` so, when a wizard is active, window state changes also feed the wizard state machine:

Find this block:

```kotlin
AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
    val pkg = event.packageName?.toString() ?: return
    Log.d(TAG, "WINDOW_STATE_CHANGED pkg=$pkg cls=${event.className} activeTarget=$activeTargetPackage")
    if (pkg == activeTargetPackage) {
        scope.launch { _runEvents.emit(RunEvent.TargetForegrounded(pkg)) }
    } else if (activeTargetPackage != null) {
        scope.launch { _runEvents.emit(RunEvent.TargetBackgrounded(activeTargetPackage!!)) }
    }
}
```

Add wizard dispatch after the existing run dispatch:

```kotlin
AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
    val pkg = event.packageName?.toString() ?: return
    Log.d(TAG, "WINDOW_STATE_CHANGED pkg=$pkg cls=${event.className} activeTarget=$activeTargetPackage wizardActive=$wizardActivePackage")
    if (pkg == activeTargetPackage) {
        scope.launch { _runEvents.emit(RunEvent.TargetForegrounded(pkg)) }
    } else if (activeTargetPackage != null) {
        scope.launch { _runEvents.emit(RunEvent.TargetBackgrounded(activeTargetPackage!!)) }
    }
    val wizSm = wizardStateMachine
    val wizPkg = wizardActivePackage
    if (wizSm != null && wizPkg != null) {
        if (pkg == wizPkg) wizSm.onEvent(WizardEvent.TargetForegrounded)
        else wizSm.onEvent(WizardEvent.TargetBackgrounded)
    }
}
```

- [ ] **Step 7.6: Build** (Android Studio: Build → Rebuild Project). Resolve any missing imports (RecordedStep, etc.).

- [ ] **Step 7.7: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/AutoDialAccessibilityService.kt
git commit -m "feat(wizard): beginWizard/endWizard on accessibility service with side-effect wiring"
```

---

## Task 8: Dialer entry point + NavGraph cleanup

**Files:**
- Modify: `app/src/main/java/com/autodial/ui/dialer/DialerScreen.kt`
- Modify: `app/src/main/java/com/autodial/ui/navigation/NavGraph.kt`

- [ ] **Step 8.1: Change Dialer's Setup tap to call `beginWizard`**

In `DialerScreen.kt`, find the `TargetToggle` composable call and replace its `onSetupWizard = onNavigateToWizard` parameter so that tapping the "Setup" chip calls the accessibility service directly instead of navigating.

Replace the existing `onNavigateToWizard: (String) -> Unit` parameter in `DialerScreen` with `onBeginWizard: (String) -> Unit`, and update the `TargetToggle` usage:

```kotlin
@Composable
fun DialerScreen(
    vm: DialerViewModel = hiltViewModel(),
    onNavigateToActiveRun: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onBeginWizard: (String) -> Unit    // was: onNavigateToWizard
) {
    // ... existing body ...
    TargetToggle(
        selected = state.targetPackage,
        bizPhoneHasRecipe = state.bizPhoneRecipe != null,
        mobileVoipHasRecipe = state.mobileVoipRecipe != null,
        onSelect = { vm.setTarget(it) },
        onSetupWizard = onBeginWizard   // was: onSetupWizard = onNavigateToWizard
    )
}
```

- [ ] **Step 8.2: Update `NavGraph.kt` — remove Wizard route, rewire Dialer**

Replace the `Screen.Dialer.route` composable and remove the `Screen.Wizard.ROUTE` composable:

```kotlin
composable(Screen.Dialer.route) {
    DialerScreen(
        onNavigateToActiveRun = { navController.navigate(Screen.ActiveRun.route) },
        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
        onNavigateToHistory = { navController.navigate(Screen.History.route) },
        onBeginWizard = { pkg ->
            com.autodial.accessibility.AutoDialAccessibilityService.instance?.beginWizard(pkg)
        }
    )
}
```

Delete the entire `composable(route = Screen.Wizard.ROUTE, arguments = ...) { ... }` block.

Also remove the `Screen.Wizard` entry from wherever it's declared (likely `navigation/Screen.kt`) — find that file and delete the Wizard sealed subclass.

- [ ] **Step 8.3: Update Onboarding + Settings screens that still navigate to Wizard**

Search for `onOpenWizard`, `onNavigateToWizard`, `Screen.Wizard(` usages across the codebase:

```bash
grep -r "Wizard\." app/src/main/java/com/autodial/ui/ | grep -v "wizard/"
```

For each call site (Onboarding, Settings), change the lambda from `{ pkg -> navController.navigate(Screen.Wizard(pkg).route) }` to:

```kotlin
{ pkg -> com.autodial.accessibility.AutoDialAccessibilityService.instance?.beginWizard(pkg) }
```

And rename the prop to `onBeginWizard` for consistency (or leave alone if it doesn't matter; consistency preferred).

- [ ] **Step 8.4: Build.** Fix any broken references to `Screen.Wizard`.

- [ ] **Step 8.5: Commit**

```bash
git add app/src/main/java/com/autodial/ui/dialer/DialerScreen.kt \
        app/src/main/java/com/autodial/ui/navigation/NavGraph.kt \
        app/src/main/java/com/autodial/ui/navigation/Screen.kt \
        app/src/main/java/com/autodial/ui/onboarding/OnboardingScreen.kt \
        app/src/main/java/com/autodial/ui/settings/SettingsScreen.kt
git commit -m "feat(wizard): entry points invoke beginWizard instead of navigating to WizardScreen"
```

---

## Task 9: Delete old WizardScreen + WizardViewModel

**Files:**
- Delete: `app/src/main/java/com/autodial/ui/wizard/WizardScreen.kt`
- Delete: `app/src/main/java/com/autodial/ui/wizard/WizardViewModel.kt`

- [ ] **Step 9.1: Delete the two files**

```bash
rm app/src/main/java/com/autodial/ui/wizard/WizardScreen.kt
rm app/src/main/java/com/autodial/ui/wizard/WizardViewModel.kt
```

- [ ] **Step 9.2: Remove any remaining imports and the directory if empty**

```bash
grep -r "com.autodial.ui.wizard" app/src/main/ app/src/test/
# Fix any straggling imports.
rmdir app/src/main/java/com/autodial/ui/wizard 2>/dev/null || true
```

- [ ] **Step 9.3: Build → Rebuild Project.** Must succeed with no references to the deleted classes.

- [ ] **Step 9.4: Run unit tests.** All `WizardStateMachineTest` + existing `RunStateMachineTest` tests pass.

- [ ] **Step 9.5: Commit**

```bash
git add -A
git commit -m "chore(wizard): remove old WizardScreen + WizardViewModel"
```

---

## Task 10: Manual device verification checklist

Run each of these on the target device. Capture logcat filtered by tag `AutoDial` for each. Mark done when verified.

- [ ] **Step 10.1: Fresh install + BizPhone happy path.** `adb uninstall com.autodial`; install fresh. Open AutoDial → Onboarding → Dialer. Tap "Setup BizPhone". Confirm BizPhone launches and overlay card appears with "STEP 1 / 4 — Tap the dial pad / keypad icon." Complete all 4 macros. Verify `✓ Recipe saved` flash then dismiss. Verify recipe row exists in DB (or just confirm a run now works).

- [ ] **Step 10.2: BizPhone run — full round-trip.** With the recipe saved, return to AutoDial, enter test number, start a 2-cycle run. Verify both cycles complete, hang-up via `AutoHangupById` path works.

- [ ] **Step 10.3: Mobile VOIP happy path.** Repeat step 10.1 for Mobile VOIP. Confirm only 4 steps (no HANG_UP prompt). Save recipe. Start a real 1-cycle run and verify hang-up via `ReuseCallButton` path ends the call.

- [ ] **Step 10.4: Cancel mid-wizard.** Start a new wizard, complete 2 macros, tap ✕ Cancel. Verify overlay dismisses, user stays in target app, existing recipe (from step 10.1) is untouched (reopen Dialer, confirm "BizPhone" chip still says recorded).

- [ ] **Step 10.5: Undo across macro boundary.** Start wizard, get to step 3 (CLEAR_DIGITS), tap Undo. Verify back at digit 10/10. Undo again → digit 9/10. Continue undoing → back to Step 1.

- [ ] **Step 10.6: Leave-and-return.** During `RECORD_DIGITS`, press Home. Overlay card updates to "Return to target app." Open BizPhone via recents. Card reverts to digit prompt with captured chips preserved. Complete the wizard normally.

- [ ] **Step 10.7: Duplicate-warning path.** BizPhone wizard, airplane mode OFF. Capture through PRESS_CALL — the fast transition should trigger duplicate warning card. Tap Re-record, enable airplane mode, re-capture PRESS_CALL. Verify clean finish.

- [ ] **Step 10.8: Accessibility service disabled mid-wizard.** Start wizard, reach step 2. In Settings, disable AutoDial's accessibility service. Verify wizard transitions to `Cancelled` (toast / card dismisses).

- [ ] **Step 10.9: Dialer STOP flow still works.** Sanity: start a run, press STOP on the run overlay. Verify the earlier STOP fixes still work — run ends cleanly, no black screen.

- [ ] **Step 10.10: Commit** any follow-up fixes found during verification. If all pass, create a final tag or note.

---

## Self-review notes

- **Spec coverage:** Every spec section maps to a task — Architecture → Tasks 2, 5, 6, 7, 8, 9; WizardStateMachine → Task 3; Runtime playback changes → Task 1; Data & persistence → Task 4; Error handling → covered across Tasks 3 (duplicate, undo, cancel, service revoked) + Task 7 (autoWipe side-effect, TargetBackgrounded/Foregrounded wiring, recipe save retry).
- **Type consistency:** `RecordedStep`, `MacroStep`, `WizardState.*`, `WizardCommand`, `WizardEvent`, `WizardSideEffect` defined once in Task 2 and used identically downstream. `HangupStrategy` fully defined in Task 1.
- **No placeholders:** Every step includes either the exact code, the exact command, or a concrete verification action.
- **TDD coverage:** Task 3 (state machine) uses full TDD. Tasks 5-8 are UI/integration and rely on Task 10's manual device tests (no unit-testable surface; documented explicitly).
