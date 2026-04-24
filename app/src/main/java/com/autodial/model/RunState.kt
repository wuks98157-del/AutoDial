package com.autodial.model

sealed class RunState {
    object Idle : RunState()
    data class Preparing(val params: RunParams) : RunState()
    data class LaunchingTarget(val params: RunParams) : RunState()
    data class OpeningDialPad(val params: RunParams, val cycle: Int) : RunState()
    data class EnteringNumber(val params: RunParams, val cycle: Int) : RunState()
    data class PressingCall(val params: RunParams, val cycle: Int) : RunState()
    data class InCall(val params: RunParams, val cycle: Int, val hangupAt: Long) : RunState()
    data class HangingUp(val params: RunParams, val cycle: Int) : RunState()
    data class Completed(val runId: Long) : RunState()
    data class StoppedByUser(val runId: Long) : RunState()
    data class Failed(val runId: Long, val reason: String) : RunState()
}

fun RunState.isTerminal(): Boolean =
    this is RunState.Completed || this is RunState.StoppedByUser || this is RunState.Failed

fun RunState.isActive(): Boolean = !isTerminal() && this !is RunState.Idle
