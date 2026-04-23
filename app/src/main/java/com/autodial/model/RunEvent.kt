package com.autodial.model

sealed class RunEvent {
    data class TargetForegrounded(val packageName: String) : RunEvent()
    data class TargetBackgrounded(val packageName: String) : RunEvent()
    data class StepActionSucceeded(val stepId: String, val outcome: String) : RunEvent()
    data class StepActionFailed(val stepId: String, val reason: String) : RunEvent()
    data class NodeAppeared(val anchorStepId: String) : RunEvent()
}
