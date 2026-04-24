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
