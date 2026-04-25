package com.autodial.service

import com.autodial.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunStateMachineTest {

    private lateinit var sm: RunStateMachine

    private val params = RunParams(
        number = "67773777",
        targetPackage = TargetApps.BIZPHONE,
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

    @Test fun openDialPadSuccessTransitionsToEnteringNumber() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.OpeningDialPad(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("OPEN_DIAL_PAD", "ok:node-primary"))
        assertTrue("expected EnteringNumber, got ${sm.state.value}",
            sm.state.value is RunState.EnteringNumber)
    }

    @Test fun numberFieldSuccessTransitionsToPressingCall() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.EnteringNumber(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("NUMBER_FIELD", "ok:set-text"))
        assertTrue("expected PressingCall, got ${sm.state.value}",
            sm.state.value is RunState.PressingCall)
    }

    @Test fun stopFromInCallHangsUpAndTransitionsToStopped() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        assertTrue("expected StoppedByUser or HangingUp, got ${sm.state.value}",
            sm.state.value is RunState.HangingUp || sm.state.value is RunState.StoppedByUser)
    }

    @Test fun stopThenHangUpSuccessRoutesToStoppedByUserInsteadOfNextCycle() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        // driveState would normally fire HANG_UP after stop flips state to HangingUp
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        assertTrue("expected StoppedByUser (not next cycle), got ${sm.state.value}",
            sm.state.value is RunState.StoppedByUser)
    }

    @Test fun stopThenHangUpFailureStillRoutesToStoppedByUser() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        // Best-effort hangup may fail (call never actually connected) — we still
        // want the run to end as Stopped, not Failed.
        sm.onEvent(RunEvent.StepActionFailed("HANG_UP", "failed:auto-hangup-node-not-visible"))
        assertTrue("expected StoppedByUser (not Failed), got ${sm.state.value}",
            sm.state.value is RunState.StoppedByUser)
    }

    @Test fun stopRequestedIsResetBetweenRuns() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.InCall(params, 0, System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        assertTrue(sm.state.value is RunState.StoppedByUser)
        // Start a fresh run; a step failure in the new run should go to Failed,
        // not leak the previous run's stopRequested and go to StoppedByUser.
        sm.forceState(RunState.Idle)
        sm.onCommand(RunCommand.Start(params, 2L))
        sm.forceState(RunState.PressingCall(params, 0))
        sm.onEvent(RunEvent.StepActionFailed("PRESS_CALL", "failed:timeout"))
        assertTrue("expected Failed on fresh run, got ${sm.state.value}",
            sm.state.value is RunState.Failed)
    }

    @Test fun stepFailedTransitionsToFailed() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.PressingCall(params, 0))
        sm.onEvent(RunEvent.StepActionFailed("PRESS_CALL", "failed:timeout"))
        assertTrue("expected Failed, got ${sm.state.value}", sm.state.value is RunState.Failed)
    }

    @Test fun completedAfterAllCycles() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 2)) // last cycle (0-indexed, plannedCycles=3)
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        assertTrue("expected Completed, got ${sm.state.value}", sm.state.value is RunState.Completed)
    }

    @Test fun bizPhoneSkipsNumberEntryOnSubsequentCycles() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        val s = sm.state.value
        assertTrue("BizPhone retains number → next cycle should go to PressingCall, got $s",
            s is RunState.PressingCall && s.cycle == 1)
    }

    @Test fun mobileVoipReEntersNumberOnSubsequentCycles() = runTest {
        val mobileVoipParams = params.copy(targetPackage = TargetApps.MOBILE_VOIP)
        sm.onCommand(RunCommand.Start(mobileVoipParams, 1L))
        sm.forceState(RunState.HangingUp(mobileVoipParams, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        val s = sm.state.value
        assertTrue("Mobile VOIP clears number → next cycle should go to EnteringNumber, got $s",
            s is RunState.EnteringNumber && s.cycle == 1)
    }

    @Test fun spamModeContinuesBeyondPlannedCycles() = runTest {
        val spamParams = params.copy(plannedCycles = 0)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.HangingUp(spamParams, 5))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        // BizPhone retains number, so next is PressingCall (not EnteringNumber)
        assertTrue("should continue to PressingCall, got ${sm.state.value}",
            sm.state.value is RunState.PressingCall)
    }

    @Test fun spamModeStopsAtSafetyCap() = runTest {
        val spamParams = params.copy(plannedCycles = 0, spamModeSafetyCap = 5)
        sm.onCommand(RunCommand.Start(spamParams, 1L))
        sm.forceState(RunState.HangingUp(spamParams, 4)) // cycle 4 = 5th (0-indexed)
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        assertTrue("expected Completed at cap, got ${sm.state.value}",
            sm.state.value is RunState.Completed)
    }

    // ── completedCycles() — drives RunRecord.completedCycles in the history. ──
    // Without a counter, finishRun() reads currentCycle() *after* the state has
    // already transitioned to a terminal state (Failed/Completed/StoppedByUser),
    // so it always recorded 0. The counter must increment on every successful
    // HANG_UP regardless of whether the next state is another cycle, Completed,
    // or StoppedByUser, and must persist when a *later* cycle fails.

    @Test fun completedCyclesIsZeroBeforeAnyHangUp() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        assertEquals(0, sm.completedCycles())
    }

    @Test fun completedCyclesIncrementsOnHangUpSuccess() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok:node-primary"))
        assertEquals(1, sm.completedCycles())
    }

    @Test fun completedCyclesAccumulatesAcrossCycles() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        sm.forceState(RunState.HangingUp(params, 1))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        assertEquals(2, sm.completedCycles())
    }

    @Test fun completedCyclesEqualsPlannedAtCompletion() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 2)) // last cycle (plannedCycles=3)
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        assertTrue(sm.state.value is RunState.Completed)
        assertEquals(3, sm.completedCycles())
    }

    @Test fun completedCyclesPersistsAfterMidRunFailure() = runTest {
        // 4 cycles dialed and hung up successfully, then cycle 5's PRESS_CALL
        // fails. completedCycles should stay at 4 — those calls really were
        // placed and ended.
        sm.onCommand(RunCommand.Start(params.copy(plannedCycles = 10), 1L))
        repeat(4) { i ->
            sm.forceState(RunState.HangingUp(params.copy(plannedCycles = 10), i))
            sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        }
        sm.forceState(RunState.PressingCall(params.copy(plannedCycles = 10), 4))
        sm.onEvent(RunEvent.StepActionFailed("PRESS_CALL", "failed:timeout"))
        assertTrue(sm.state.value is RunState.Failed)
        assertEquals(4, sm.completedCycles())
    }

    @Test fun completedCyclesPersistsAfterStopMidRun() = runTest {
        sm.onCommand(RunCommand.Start(params.copy(plannedCycles = 10), 1L))
        // 6 cycles completed successfully
        repeat(6) { i ->
            sm.forceState(RunState.HangingUp(params.copy(plannedCycles = 10), i))
            sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        }
        // user STOPs during cycle 7's call
        sm.forceState(RunState.InCall(params.copy(plannedCycles = 10), 6,
            System.currentTimeMillis() + 10_000L))
        sm.onCommand(RunCommand.Stop)
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok")) // stop's hangup completes
        assertTrue(sm.state.value is RunState.StoppedByUser)
        // The user's stop-triggered hangup *did* end a real call, so it counts.
        assertEquals(7, sm.completedCycles())
    }

    @Test fun completedCyclesResetsBetweenRuns() = runTest {
        sm.onCommand(RunCommand.Start(params, 1L))
        sm.forceState(RunState.HangingUp(params, 0))
        sm.onEvent(RunEvent.StepActionSucceeded("HANG_UP", "ok"))
        assertEquals(1, sm.completedCycles())
        sm.forceState(RunState.Idle)
        sm.onCommand(RunCommand.Start(params, 2L))
        assertEquals(0, sm.completedCycles())
    }
}
