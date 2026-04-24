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
        if (step.stepId !in s.queue) return

        val newCaptured = s.captured + (step.stepId to step)
        val newQueue = s.queue - step.stepId
        val newUndo = s.undoStack + step.stepId

        if (newQueue.isNotEmpty()) {
            _state.value = s.copy(
                queue = newQueue, captured = newCaptured,
                undoStack = newUndo, lastCapture = step
            )
            return
        }
        val next = nextMacro(s.macro)
        if (next == null) {
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
            _sideEffects.tryEmit(WizardSideEffect.SaveRecipe(s.targetPackage, newCaptured))
            _state.value = WizardState.Completed(recipeSaved = null)
            return
        }
        val advanced = s.copy(
            macro = next, queue = queueFor(next),
            captured = newCaptured, undoStack = newUndo, lastCapture = step
        )
        _state.value = advanced
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
        val macro = if (newUndo.isEmpty()) MacroStep.OPEN_DIAL_PAD
                    else macroOf(newUndo.last())
        val fullQueue = queueFor(macro)
        val capturedInMacro = newCaptured.keys.filter { macroOf(it) == macro }
        val newQueue = fullQueue - capturedInMacro.toSet()
        _state.value = s.copy(
            macro = macro, queue = newQueue,
            captured = newCaptured, undoStack = newUndo,
            lastCapture = newCaptured[newUndo.lastOrNull()]?.takeIf {
                newUndo.isNotEmpty() && macroOf(newUndo.last()) == macro
            }
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
