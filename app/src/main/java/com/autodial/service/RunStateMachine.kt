package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunStateMachine {

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var runId: Long = 0L

    // Set by handleStop() and consulted after each step outcome so a user
    // STOP routes to StoppedByUser instead of advancing to the next cycle —
    // otherwise STOP during InCall silently hangs up and keeps looping.
    private var stopRequested = false

    // Successful HANG_UP outcomes since this run started. Drives
    // RunRecord.completedCycles, which finishRun() must read at terminal time —
    // currentCycle() reads the live state and returns 0 for terminal states,
    // so it always under-counted (history showed 0/N for both clean and
    // mid-run-stopped runs).
    private var completedCycles = 0

    fun isStopRequested(): Boolean = stopRequested

    fun completedCycles(): Int = completedCycles

    fun onCommand(cmd: RunCommand) {
        when (cmd) {
            is RunCommand.Start -> {
                if (_state.value !is RunState.Idle) return
                runId = cmd.runId
                stopRequested = false
                completedCycles = 0
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
                _state.value = if (stopRequested) RunState.StoppedByUser(runId)
                               else RunState.Failed(runId, event.reason)

            event is RunEvent.StepActionSucceeded -> handleStepSucceeded(event.stepId, s)

            else -> {}
        }
    }

    private fun handleStepSucceeded(stepId: String, s: RunState) {
        _state.value = when {
            stepId == "OPEN_DIAL_PAD" && s is RunState.OpeningDialPad ->
                RunState.EnteringNumber(s.params, s.cycle)

            stepId == "NUMBER_FIELD" && s is RunState.EnteringNumber ->
                RunState.PressingCall(s.params, s.cycle)

            stepId == "PRESS_CALL" && s is RunState.PressingCall ->
                RunState.InCall(s.params, s.cycle,
                    System.currentTimeMillis() + s.params.hangupSeconds * 1000L)

            stepId == "HANG_UP" && s is RunState.HangingUp -> {
                // Cycle index is 0-based; a successful HANG_UP means cycle
                // s.cycle is fully done, so the count of completed cycles
                // becomes s.cycle + 1.
                completedCycles = s.cycle + 1
                if (stopRequested) {
                    RunState.StoppedByUser(runId)
                } else {
                    val nextCycle = s.cycle + 1
                    val done = when {
                        s.params.plannedCycles == 0 -> nextCycle >= s.params.spamModeSafetyCap
                        else -> nextCycle >= s.params.plannedCycles
                    }
                    when {
                        done -> RunState.Completed(runId)
                        TargetApps.retainsNumberAfterHangup(s.params.targetPackage) ->
                            RunState.PressingCall(s.params, nextCycle)
                        else -> RunState.EnteringNumber(s.params, nextCycle)
                    }
                }
            }

            else -> _state.value
        }
    }

    private fun handleStop() {
        val s = _state.value
        if (s.isTerminal() || s is RunState.Idle) return
        stopRequested = true
        when (s) {
            // InCall: flip to HangingUp so the overlay/notification show the
            // stop intent. driveState(InCall) watches for this transition and
            // skips its hangup-timer wait so the hangup fires immediately.
            is RunState.InCall -> _state.value = RunState.HangingUp(s.params, s.cycle)
            // PressingCall: don't transition. The in-flight PRESS_CALL will
            // complete naturally, the state machine will advance to InCall,
            // and driveState(InCall) will see stopRequested and short-circuit
            // straight into the hangup. Trying to interrupt a call that's
            // mid-placement leaves the line hung.
            is RunState.PressingCall -> { /* stopRequested is enough */ }
            // HangingUp: a hangup is already in flight. Let it finish; the
            // stopRequested gate in handleStepSucceeded routes to StoppedByUser.
            is RunState.HangingUp -> { /* stopRequested is enough */ }
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

    fun markHangingUp() {
        val s = _state.value
        if (s is RunState.InCall) _state.value = RunState.HangingUp(s.params, s.cycle)
    }

    fun markHangupComplete(runId: Long, completed: Boolean) {
        _state.value = if (completed) RunState.Completed(runId) else RunState.StoppedByUser(runId)
    }

    fun forceState(state: RunState) { _state.value = state }  // test-only
}
