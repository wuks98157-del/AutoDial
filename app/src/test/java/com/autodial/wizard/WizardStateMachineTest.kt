package com.autodial.wizard

import com.autodial.model.RecordedStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
        backgroundScope.launch {
            sm.sideEffects.collect { effects.add(it) }
        }
        // Let the collector actually subscribe before we start firing emits —
        // SharedFlow has no replay so pre-subscription events would be lost.
        testScheduler.runCurrent()
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        testScheduler.runCurrent()  // drain the side-effect collector
        val s = sm.state.value as WizardState.Completed
        assertNull(s.recipeSaved)
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
        backgroundScope.launch {
            sm.sideEffects.collect { effects.add(it) }
        }
        // Let the collector actually subscribe before we start firing emits —
        // SharedFlow has no replay so pre-subscription events would be lost.
        testScheduler.runCurrent()
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        testScheduler.runCurrent()  // drain the side-effect collector
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
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
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

    @Test fun retrySaveAfterFailureResetsToSavingAndReEmitsSaveRecipe() = runTest {
        val effects = mutableListOf<WizardSideEffect>()
        backgroundScope.launch { sm.sideEffects.collect { effects.add(it) } }
        testScheduler.runCurrent()
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        sm.onEvent(WizardEvent.RecipeSaveResult(success = false, error = "disk full"))
        testScheduler.runCurrent()
        assertEquals(false, (sm.state.value as WizardState.Completed).recipeSaved)

        val effectsBeforeRetry = effects.size
        sm.onCommand(WizardCommand.RetrySave)
        testScheduler.runCurrent()
        val s = sm.state.value as WizardState.Completed
        assertNull("retry should reset recipeSaved to null (saving)", s.recipeSaved)
        assertTrue(
            "retry should emit another SaveRecipe",
            effects.size > effectsBeforeRetry &&
                effects.last() is WizardSideEffect.SaveRecipe
        )

        sm.onEvent(WizardEvent.RecipeSaveResult(success = true))
        assertEquals(true, (sm.state.value as WizardState.Completed).recipeSaved)
    }

    @Test fun retrySaveIgnoredWhileSaveInProgress() = runTest {
        sm.onCommand(WizardCommand.Start(pkg))
        sm.onEvent(WizardEvent.Captured(step("OPEN_DIAL_PAD")))
        (0..9).forEach { sm.onEvent(WizardEvent.Captured(step("DIGIT_$it"))) }
        sm.onEvent(WizardEvent.Captured(step("CLEAR_DIGITS", rid = "clear")))
        sm.onEvent(WizardEvent.Captured(step("PRESS_CALL", rid = "call")))
        // state is Completed(recipeSaved=null) — saving in progress
        sm.onCommand(WizardCommand.RetrySave)
        assertNull((sm.state.value as WizardState.Completed).recipeSaved)
    }
}
