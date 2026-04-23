package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunStateMachine {

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var runId: Long = 0L

    fun onCommand(cmd: RunCommand) {
        when (cmd) {
            is RunCommand.Start -> {
                if (_state.value !is RunState.Idle) return
                runId = cmd.runId
                _state.value = RunState.Preparing(cmd.params)
            }
            RunCommand.Stop -> handleStop()
        }
    }

    fun onEvent(event: RunEvent) {
        val s = _state.value
        when {
            event is RunEvent.TargetForegrounded && s is RunState.LaunchingTarget ->
                _state.value = RunState.OpeningDialPad(s.params, 0)

            event is RunEvent.TargetBackgrounded && s.isActive() && s !is RunState.LaunchingTarget ->
                _state.value = RunState.Failed(runId, "failed:target-app-closed")

            event is RunEvent.StepActionFailed ->
                _state.value = RunState.Failed(runId, event.reason)

            event is RunEvent.StepActionSucceeded -> handleStepSucceeded(event.stepId, s)

            else -> {}
        }
    }

    private fun handleStepSucceeded(stepId: String, s: RunState) {
        _state.value = when {
            stepId == "OPEN_DIAL_PAD" && s is RunState.OpeningDialPad ->
                RunState.TypingDigits(s.params, s.cycle, 0)

            stepId.startsWith("DIGIT_") && s is RunState.TypingDigits -> {
                val nextDigitIndex = s.digitIndex + 1
                if (nextDigitIndex < s.params.number.length)
                    RunState.TypingDigits(s.params, s.cycle, nextDigitIndex)
                else
                    RunState.PressingCall(s.params, s.cycle)
            }

            stepId == "PRESS_CALL" && s is RunState.PressingCall ->
                RunState.InCall(s.params, s.cycle,
                    System.currentTimeMillis() + s.params.hangupSeconds * 1000L)

            stepId in listOf("HANG_UP_CONNECTED", "HANG_UP_RINGING") && s is RunState.HangingUp ->
                RunState.ReturningToDialPad(s.params, s.cycle)

            stepId == "RETURN_TO_DIAL_PAD" && s is RunState.ReturningToDialPad -> {
                val nextCycle = s.cycle + 1
                val done = when {
                    s.params.plannedCycles == 0 -> nextCycle >= s.params.spamModeSafetyCap
                    else -> nextCycle >= s.params.plannedCycles
                }
                if (done) RunState.Completed(runId)
                else RunState.TypingDigits(s.params, nextCycle, 0)
            }

            else -> _state.value
        }
    }

    private fun handleStop() {
        val s = _state.value
        if (s.isTerminal() || s is RunState.Idle) return
        when (s) {
            is RunState.InCall -> _state.value = RunState.HangingUp(s.params, s.cycle)
            is RunState.PressingCall -> _state.value = RunState.HangingUp(s.params, s.cycle)
            else -> _state.value = RunState.StoppedByUser(runId)
        }
    }

    fun advanceToLaunching() {
        val s = _state.value
        if (s is RunState.Preparing) _state.value = RunState.LaunchingTarget(s.params)
    }

    fun advanceToInCall(params: RunParams, cycle: Int) {
        _state.value = RunState.InCall(params, cycle,
            System.currentTimeMillis() + params.hangupSeconds * 1000L)
    }

    fun markHangupComplete(runId: Long, completed: Boolean) {
        _state.value = if (completed) RunState.Completed(runId) else RunState.StoppedByUser(runId)
    }

    fun forceState(state: RunState) { _state.value = state }  // test-only
}
