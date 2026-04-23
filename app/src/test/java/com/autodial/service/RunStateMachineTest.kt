package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunStateMachineTest {

    private lateinit var sm: RunStateMachine

    private val params = RunParams(
        number = "67773777",
        targetPackage = "com.b3networks.bizphone",
        plannedCycles = 3,
        hangupSeconds = 10,
        spamModeSafetyCap = 9999,
        interDigitDelayMs = 400L
    )

    @Before fun setUp() { sm = RunStateMachine() }

    @Test fun initialStateIsIdle() = runTest {
        assertEquals(RunState.Idle, sm.state.value)
    }

    @Test fun startTransitionsToPreparing() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        assertTrue(sm.state.value is RunState.Preparing)
    }

    @Test fun targetForegroundedAfterLaunchTransitionsToOpenDialPad() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.advanceToLaunching()
        sm.onEvent(RunEvent.TargetForegrounded(params.targetPackage))
        assertTrue("expected OpeningDialPad, got ${sm.state.value}",
            sm.state.value is RunState.OpeningDialPad)
    }

    @Test fun stopFromInCallHangsUpAndTransitionsToStopped() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        assertTrue("expected StoppedByUser or HangingUp, got ${sm.state.value}",
            sm.state.value is RunState.HangingUp || sm.state.value is RunState.StoppedByUser)
    }

    @Test fun stepFailedTransitionsToFailed() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.PressingCall(params, 0))
        sm.onEvent(RunEvent.StepActionFailed("PRESS_CALL", "failed:timeout"))
        assertTrue("expected Failed, got ${sm.state.value}", sm.state.value is RunState.Failed)
    }

    @Test fun completedAfterAllCycles() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.ReturningToDialPad(params, 2)) // last cycle (0-indexed)
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("expected Completed, got ${sm.state.value}", sm.state.value is RunState.Completed)
    }

    @Test fun spamModeContinuesBeyondPlannedCycles() = runTest {
        val spamParams = params.copy(plannedCycles = 0)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.ReturningToDialPad(spamParams, 5))
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("should continue, not complete", sm.state.value is RunState.TypingDigits)
    }

    @Test fun spamModeStopsAtSafetyCap() = runTest {
        val spamParams = params.copy(plannedCycles = 0, spamModeSafetyCap = 5)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.ReturningToDialPad(spamParams, 4)) // cycle 4 = 5th (0-indexed)
        sm.onEvent(RunEvent.StepActionSucceeded("RETURN_TO_DIAL_PAD", "ok:node-primary"))
        assertTrue("expected Completed at cap, got ${sm.state.value}",
            sm.state.value is RunState.Completed)
    }
}
