package com.autodial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autodial.R
import com.autodial.accessibility.AutoDialAccessibilityService
import com.autodial.accessibility.StepOutcome
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.data.db.entity.RunStepEvent
import com.autodial.data.repository.HistoryRepository
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.*
import com.autodial.overlay.OverlayController
import com.autodial.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import javax.inject.Inject

@AndroidEntryPoint
class RunForegroundService : Service() {

    @Inject lateinit var recipeRepo: RecipeRepository
    @Inject lateinit var historyRepo: HistoryRepository

    companion object {
        const val CHANNEL_ID = "autodial_run"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.autodial.ACTION_STOP"

        private val _publicState = MutableStateFlow<RunState>(RunState.Idle)
        val publicState: StateFlow<RunState> = _publicState.asStateFlow()

        // Logcat tag. Filter in Android Studio's Logcat pane with "tag:AutoDial"
        // or from a terminal: `adb logcat -s AutoDial:*`.
        const val TAG = "AutoDial"

        fun start(context: Context, params: RunParams) {
            val intent = Intent(context, RunForegroundService::class.java).apply {
                putExtra("number", params.number)
                putExtra("targetPackage", params.targetPackage)
                putExtra("plannedCycles", params.plannedCycles)
                putExtra("hangupSeconds", params.hangupSeconds)
                putExtra("spamModeSafetyCap", params.spamModeSafetyCap)
                putExtra("interDigitDelayMs", params.interDigitDelayMs)
            }
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateMachine = RunStateMachine()
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayController: OverlayController? = null
    private var runId: Long = 0L
    private var currentParams: RunParams? = null
    private var currentRunRecord: RunRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayController = OverlayController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRun(); return START_NOT_STICKY }

        val params = intent?.toRunParams() ?: return START_NOT_STICKY
        currentParams = params
        startForeground(NOTIF_ID, buildNotification(params.number, "Starting…"))
        acquireWakeLock()
        launchRun(params)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        overlayController?.dismiss()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Run lifecycle ──────────────────────────────────────────────────────

    private fun launchRun(params: RunParams) {
        scope.launch {
            val now = System.currentTimeMillis()
            val record = RunRecord(
                startedAt = now, endedAt = now,
                number = params.number, targetPackage = params.targetPackage,
                plannedCycles = params.plannedCycles, completedCycles = 0,
                hangupSeconds = params.hangupSeconds,
                status = RunStatus.DONE, failureReason = null
            )
            runId = historyRepo.startRun(record)
            currentRunRecord = record.copy(id = runId)
            Log.i(TAG, "=== RUN START id=$runId number=${params.number} target=${params.targetPackage} cycles=${params.plannedCycles} hangupS=${params.hangupSeconds} ===")

            val accessService = AutoDialAccessibilityService.instance
            if (accessService == null) {
                finishRun(RunStatus.FAILED, "failed:service-not-bound")
                return@launch
            }

            accessService.beginRun(params)
            accessService.startSelfCheckLoop()
            stateMachine.onCommand(RunCommand.Start(params, runId))
            _publicState.value = stateMachine.state.value

            // Collect accessibility events → state machine
            launch {
                accessService.runEvents.collect { event ->
                    Log.d(TAG, "event: $event")
                    stateMachine.onEvent(event)
                    _publicState.value = stateMachine.state.value
                }
            }

            // Drive the state machine
            stateMachine.state.collect { state ->
                Log.i(TAG, "state → ${state::class.simpleName}")
                _publicState.value = state
                overlayController?.updateState(state)
                updateNotification(state)
                driveState(state, params, accessService)
            }
        }
    }

    private suspend fun driveState(
        state: RunState,
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ) {
        when (state) {
            is RunState.Preparing -> {
                // Launch target app — we are foreground right now, so no BAL
                // restriction. NEW_TASK only (no CLEAR_TASK): if the user had
                // BizPhone already open on its dial pad, NEW_TASK brings that
                // task to the front in its existing state, skipping the full
                // splash/init flow. CLEAR_TASK would kill it and force a cold
                // restart through LauncherActivity — which on Vivo re-triggers
                // BizPhone's BLUETOOTH_CONNECT permission dialog every launch
                // and adds ~1.5s of friction even when permissions are denied.
                // For best results testers should open BizPhone on the dial
                // pad before tapping START; if they don't, NEW_TASK still
                // launches fresh as before and the existing
                // waitForTargetForeground / isDialPadAlreadyOpen guards
                // handle the splash flow.
                val launchIntent = packageManager.getLaunchIntentForPackage(params.targetPackage)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent == null) {
                    stateMachine.onEvent(RunEvent.StepActionFailed("LAUNCH", "failed:target-not-installed"))
                    return
                }
                startActivity(launchIntent)
                stateMachine.advanceToLaunching()
                withTimeout(5000L) {
                    stateMachine.state.first { it is RunState.OpeningDialPad || it.isTerminal() }
                }
            }

            is RunState.OpeningDialPad -> {
                // The state machine arrives here the instant ANY target-app window
                // appears — which may include the splash / login activity before
                // the real UI has mounted. Tapping OPEN_DIAL_PAD at that moment
                // lands on nothing useful. A brief settle + waitForRecordedNode
                // polling (200 ms cadence, 2500 ms budget) reliably catches the
                // real dial pad. The post-tap delay lets the open animation run.
                Log.d(TAG, "OpeningDialPad: initial 800ms settle")
                delay(800L)

                // System dialogs (BizPhone's first-launch BLUETOOTH_CONNECT
                // permission prompt on Vivo, OEM "are you sure?" prompts, etc.)
                // can land on top of the target app right when we're about to
                // tap. Their accessibility tree has none of our recorded
                // resourceIds, so Tier 1/2 fail and Tier 3 raw-coord-taps land
                // on the dialog's buttons. Wait for the target to actually own
                // the foreground window before proceeding. ~5s is enough to
                // outlast auto-dismissed permission dialogs (Vivo: ~1.5s).
                if (!waitForTargetForeground(state.params.targetPackage, 5000L, accessService)) {
                    val foregroundPkg = accessService.rootNode()?.packageName?.toString() ?: "unknown"
                    Log.w(TAG, "OpeningDialPad: target not foreground after 5s — pkg=$foregroundPkg")
                    stateMachine.onEvent(RunEvent.StepActionFailed(
                        "OPEN_DIAL_PAD", "failed:foreground-not-target:$foregroundPkg"))
                    return
                }

                // BizPhone (and likely Mobile VOIP) preserve their last-shown
                // fragment across launches. After a run that ended on the dial
                // pad, a subsequent run finds BizPhone with the dial pad ALREADY
                // OPEN — and the keypad icon is a toggle, so tapping it then
                // CLOSES the dial pad. Logcat from that failure mode: every
                // DIGIT_X step finds zero nodes and falls to Tier 3 coord taps
                // landing on whatever is underneath (call history items, the
                // contacts tab). Detect "already open" via PRESS_CALL visibility
                // and skip the toggle tap entirely.
                if (isDialPadAlreadyOpen(state.params, accessService)) {
                    Log.i(TAG, "OpeningDialPad: dial pad already open — " +
                        "skipping OPEN_DIAL_PAD tap (would toggle it off)")
                    historyRepo.logStepEvent(RunStepEvent(
                        runId = runId, cycleIndex = currentCycle(), stepId = "OPEN_DIAL_PAD",
                        at = System.currentTimeMillis(),
                        outcome = "ok:already-open", detail = null
                    ))
                    stateMachine.onEvent(
                        RunEvent.StepActionSucceeded("OPEN_DIAL_PAD", "ok:already-open"))
                } else {
                    waitForRecordedNode(state.params.targetPackage, "OPEN_DIAL_PAD", 2500L, accessService)
                    executeStep("OPEN_DIAL_PAD", state.params, accessService)
                    delay(400L)
                }
            }

            is RunState.EnteringNumber -> {
                setNumber(state.params, accessService)
                delay(200L)
            }

            is RunState.PressingCall -> {
                // Cycle N+1 on BizPhone returns here straight from HANG_UP, while
                // InCallActivity is still dismissing — tapping `:id/call` at that
                // moment finds zero candidates and falls through to a blind Tier 3
                // coord tap on the wrong surface, silently advancing the state
                // machine into a phantom InCall that trips auto-hangup-not-visible
                // 10s later. Wait for the dial pad node to actually be reachable
                // before firing. No-op (~10ms) when we're already on the dial pad.
                waitForRecordedNode(state.params.targetPackage, "PRESS_CALL", 2500L, accessService)
                executeStep("PRESS_CALL", state.params, accessService)
            }

            is RunState.InCall -> {
                // Race the hangup timer against three exits:
                //   1. STOP (state flips InCall→HangingUp) — fall through to hangup
                //   2. Target ends the call on its own (dial pad reappears) —
                //      skip hangup, synthesize HANG_UP success, advance cycle
                //   3. Timer expiry — normal hangup path
                // The existing code only handled #1 and #3. When BizPhone
                // returned to its dial pad mid-timer (invalid number / recipient
                // hung up), AutoDial sat there waiting, then tried clearCallButton
                // on a screen where it no longer existed, and Failed the run.
                val callEndedByTarget = waitForInCallToEnd(state, accessService)

                if (callEndedByTarget) {
                    Log.i(TAG, "InCall cycle=${state.cycle}: target returned to dial pad — " +
                        "synthesizing HANG_UP success (skipping hangup tap)")
                    // Flip to HangingUp first — handleStepSucceeded only routes
                    // HANG_UP success when state is HangingUp.
                    stateMachine.markHangingUp()
                    historyRepo.logStepEvent(RunStepEvent(
                        runId = runId, cycleIndex = currentCycle(), stepId = "HANG_UP",
                        at = System.currentTimeMillis(),
                        outcome = "ok:call-ended-by-target", detail = null
                    ))
                    stateMachine.onEvent(
                        RunEvent.StepActionSucceeded("HANG_UP", "ok:call-ended-by-target"))
                } else {
                    stateMachine.markHangingUp()
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
                }
                // inter-cycle settle — short pause after hangup before the
                // next cycle's PressingCall. waitForRecordedNode(PRESS_CALL)
                // picks up any residual transition delay.
                delay(300L)
            }

            is RunState.HangingUp -> { /* driven by IN_CALL above */ }

            is RunState.Completed -> finishRun(RunStatus.DONE, null)
            is RunState.StoppedByUser -> finishRun(RunStatus.STOPPED, null)
            is RunState.Failed -> finishRun(RunStatus.FAILED, state.reason)
            else -> {}
        }
    }

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
        Log.i(TAG, "EXEC $logicalStepId (data=$stepId) rid=${step.resourceId} text=${step.text} cls=${step.className} " +
            "bounds=rel(${"%.3f".format(step.boundsRelX)},${"%.3f".format(step.boundsRelY)}," +
            "${"%.3f".format(step.boundsRelW)}x${"%.3f".format(step.boundsRelH)})")
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

    private suspend fun setNumber(
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ) {
        val steps = recipeRepo.getSteps(params.targetPackage)

        // Phase 1: clear any leftover digits. Apps whose backspace clears all
        // digits when held (BizPhone) get a single press-and-hold — faster than
        // looping taps and it doesn't look like a scroll/flick to the target.
        // Others fall back to a bounded tap loop (~15 is enough for any real
        // phone number) with enough delay between taps to register as discrete
        // clicks instead of a swipe.
        val clearStep = steps.firstOrNull { it.stepId == "CLEAR_DIGITS" }
        val longPressMs = TargetApps.clearDigitsLongPressMs(params.targetPackage)
        if (clearStep != null && longPressMs != null) {
            Log.i(TAG, "CLEAR_DIGITS long-press ${longPressMs}ms rid=${clearStep.resourceId} " +
                "rel=(${"%.3f".format(clearStep.boundsRelX)},${"%.3f".format(clearStep.boundsRelY)})")
            val outcome = accessService.longPressStep(clearStep, longPressMs)
            val out = when (outcome) {
                is StepOutcome.Ok -> outcome.outcome
                is StepOutcome.Failed -> outcome.reason
            }
            Log.i(TAG, "CLEAR_DIGITS long-press → $out")
        } else if (clearStep != null) {
            Log.i(TAG, "CLEAR_DIGITS × 15 @ 120ms rid=${clearStep.resourceId} " +
                "rel=(${"%.3f".format(clearStep.boundsRelX)},${"%.3f".format(clearStep.boundsRelY)})")
            repeat(15) { i ->
                val outcome = accessService.executeStep(clearStep)
                val out = when (outcome) {
                    is StepOutcome.Ok -> outcome.outcome
                    is StepOutcome.Failed -> outcome.reason
                }
                Log.d(TAG, "CLEAR_DIGITS[$i] → $out")
                delay(120L)
            }
        } else {
            Log.w(TAG, "CLEAR_DIGITS step missing from recipe — skipping clear phase")
        }

        // Phase 2: tap each digit button in the target number. Each DIGIT_0..DIGIT_9
        // was individually recorded so UiPlayer's tier fallback (resourceId →
        // text+class+bounds → raw coordinate tap) has full identity info per button.
        for (digit in params.number) {
            val stepId = "DIGIT_$digit"
            val digitStep = steps.firstOrNull { it.stepId == stepId }
            if (digitStep == null) {
                historyRepo.logStepEvent(RunStepEvent(
                    runId = runId, cycleIndex = currentCycle(), stepId = stepId,
                    at = System.currentTimeMillis(), outcome = "failed:step-not-recorded", detail = null
                ))
                stateMachine.onEvent(RunEvent.StepActionFailed("NUMBER_FIELD", "failed:digit-$digit-not-recorded"))
                return
            }
            Log.i(TAG, "EXEC $stepId rid=${digitStep.resourceId} text=${digitStep.text} " +
                "rel=(${"%.3f".format(digitStep.boundsRelX)},${"%.3f".format(digitStep.boundsRelY)})")
            val outcome = accessService.executeStep(digitStep)
            val outStr = when (outcome) {
                is StepOutcome.Ok -> outcome.outcome
                is StepOutcome.Failed -> outcome.reason
            }
            Log.i(TAG, "EXEC $stepId result=$outStr")
            historyRepo.logStepEvent(RunStepEvent(
                runId = runId, cycleIndex = currentCycle(), stepId = stepId,
                at = System.currentTimeMillis(), outcome = outStr, detail = null
            ))
            if (outcome is StepOutcome.Failed) {
                stateMachine.onEvent(RunEvent.StepActionFailed("NUMBER_FIELD", outStr))
                return
            }
            delay(params.interDigitDelayMs)
        }

        stateMachine.onEvent(RunEvent.StepActionSucceeded("NUMBER_FIELD", "ok:digits-tapped"))
    }

    private fun currentCycle(): Int {
        return when (val s = stateMachine.state.value) {
            is RunState.EnteringNumber -> s.cycle
            is RunState.PressingCall -> s.cycle
            is RunState.InCall -> s.cycle
            is RunState.HangingUp -> s.cycle
            else -> 0
        }
    }

    // Poll the accessibility tree until the node we recorded for [stepId] is
    // reachable — i.e. BizPhone has settled past splash/login onto the screen the
    // user was actually looking at when they taught the step. Returns early on
    // match, otherwise falls through after [timeoutMs] so we still attempt the tap
    // (better to try and fail loudly than hang forever). No-op if the recorded
    // step has no resourceId to poll for.
    // Hang up without needing a recorded HANG_UP step. Retries briefly in case
    // the in-call window is still animating in when the hangup timer fires.
    // Emits the usual StepActionSucceeded/Failed so the state machine can
    // advance to the next cycle (or Completed) just like a normal step.
    private suspend fun autoHangup(
        resourceId: String,
        accessService: AutoDialAccessibilityService
    ) {
        Log.i(TAG, "AUTO-HANGUP rid=$resourceId")
        var outcome: StepOutcome = StepOutcome.Failed("failed:auto-hangup-not-attempted")
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            outcome = accessService.tapByResourceId(resourceId)
            if (outcome is StepOutcome.Ok) break
            delay(250L)
        }
        val outStr = when (outcome) {
            is StepOutcome.Ok -> outcome.outcome
            is StepOutcome.Failed -> outcome.reason
        }
        Log.i(TAG, "AUTO-HANGUP result=$outStr")
        historyRepo.logStepEvent(RunStepEvent(
            runId = runId, cycleIndex = currentCycle(), stepId = "HANG_UP",
            at = System.currentTimeMillis(), outcome = outStr, detail = null
        ))
        when (outcome) {
            is StepOutcome.Ok -> stateMachine.onEvent(RunEvent.StepActionSucceeded("HANG_UP", outStr))
            is StepOutcome.Failed -> stateMachine.onEvent(RunEvent.StepActionFailed("HANG_UP", outStr))
        }
    }

    // Race the hangup-timer wait against polling for the target app's dial pad
    // returning. Returns true if the dial pad reappeared (call ended on the
    // target's side — invalid number, recipient hung up, network drop) so the
    // caller can skip the hangup tap and synthesize a HANG_UP success.
    // Returns false on timer expiry or STOP — caller does its normal hangup.
    //
    // Detection signal: the recorded PRESS_CALL node becoming visible again.
    // While the in-call activity is foreground, that node is unreachable; when
    // the target returns to its dial pad, it reappears. Gated by
    // TargetApps.detectsCallEndByDialPad — Mobile VOIP shares the call/end-call
    // view, so visibility there doesn't disambiguate state.
    private suspend fun waitForInCallToEnd(
        state: RunState.InCall,
        accessService: AutoDialAccessibilityService
    ): Boolean = coroutineScope {
        val remaining = state.hangupAt - System.currentTimeMillis()
        if (remaining <= 0 || stateMachine.isStopRequested()) return@coroutineScope false

        val pressCallRid: String? = recipeRepo.getSteps(state.params.targetPackage)
            .firstOrNull { it.stepId == "PRESS_CALL" }?.resourceId
            ?.takeIf { TargetApps.detectsCallEndByDialPad(state.params.targetPackage) }

        // Timer arm: existing wait — completes on timeout, on STOP flipping the
        // state away from InCall, or on driveState advancing past InCall for
        // any other reason. Always returns false (timer/STOP path).
        val timerJob = async {
            withTimeoutOrNull(remaining) {
                stateMachine.state.first { it !is RunState.InCall }
            }
            false
        }

        // Detection arm: poll the live a11y tree for PRESS_CALL becoming
        // visible. The 2s settle prevents false positives during the in-call
        // activity's enter animation, when the dial pad surface beneath can
        // still report visible briefly.
        val watchJob = pressCallRid?.let { rid ->
            async {
                delay(2000L)
                while (isActive) {
                    val root = accessService.rootNode()
                    val hits = root?.findAccessibilityNodeInfosByViewId(rid) ?: emptyList()
                    if (hits.any { it.isVisibleToUser }) {
                        Log.i(TAG, "InCall watcher: PRESS_CALL visible — call ended by target")
                        return@async true
                    }
                    delay(400L)
                }
                false
            }
        }

        val result = if (watchJob != null) {
            select<Boolean> {
                timerJob.onAwait { it }
                watchJob.onAwait { it }
            }
        } else {
            timerJob.await()
        }
        timerJob.cancel()
        watchJob?.cancel()
        result
    }

    // Poll until the foreground accessibility window's package matches the
    // target. Returns true on match, false on timeout. Used to outlast
    // transient system dialogs (BizPhone's first-launch BLUETOOTH_CONNECT
    // prompt on Vivo, OEM "are you sure?" prompts, the system "open with"
    // chooser, etc.) that land on top of the target right when we're about
    // to start tapping. Without this guard, Tier-3 coord taps land on the
    // dialog's buttons.
    private suspend fun waitForTargetForeground(
        target: String,
        timeoutMs: Long,
        accessService: AutoDialAccessibilityService
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeen: String? = null
        while (System.currentTimeMillis() < deadline) {
            val pkg = accessService.rootNode()?.packageName?.toString()
            if (pkg == target) {
                if (lastSeen != null && lastSeen != target) {
                    Log.i(TAG, "waitForTargetForeground: $target reached (was $lastSeen)")
                }
                return true
            }
            if (pkg != lastSeen) {
                Log.d(TAG, "waitForTargetForeground: foreground=$pkg (waiting for $target)")
                lastSeen = pkg
            }
            delay(200L)
        }
        return false
    }

    // True if the recorded PRESS_CALL node is currently visible — used as a
    // sentinel for "the dial pad fragment is already showing". When true, the
    // OpeningDialPad driver skips the OPEN_DIAL_PAD tap to avoid toggling the
    // dial pad off on apps where the keypad icon is a toggle.
    private suspend fun isDialPadAlreadyOpen(
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ): Boolean {
        val sentinelRid = recipeRepo.getSteps(params.targetPackage)
            .firstOrNull { it.stepId == "PRESS_CALL" }?.resourceId
            ?: return false
        val root = accessService.rootNode() ?: return false
        val hits = root.findAccessibilityNodeInfosByViewId(sentinelRid) ?: return false
        return hits.any { it.isVisibleToUser }
    }

    private suspend fun waitForRecordedNode(
        targetPackage: String,
        stepId: String,
        timeoutMs: Long,
        accessService: AutoDialAccessibilityService
    ) {
        val step = recipeRepo.getSteps(targetPackage).firstOrNull { it.stepId == stepId }
        val rid = step?.resourceId
        if (rid == null) {
            Log.d(TAG, "waitForRecordedNode($stepId): no resourceId, skipping poll")
            return
        }
        Log.d(TAG, "waitForRecordedNode($stepId) rid=$rid timeout=${timeoutMs}ms")
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = accessService.rootNode()
            val hits = root?.findAccessibilityNodeInfosByViewId(rid) ?: emptyList()
            if (hits.any { it.isVisibleToUser }) {
                Log.d(TAG, "waitForRecordedNode($stepId) FOUND after ${System.currentTimeMillis() - t0}ms (${hits.size} candidate(s))")
                return
            }
            delay(200L)
        }
        Log.w(TAG, "waitForRecordedNode($stepId) TIMEOUT — proceeding anyway")
    }

    private fun stopRun() {
        stateMachine.onCommand(RunCommand.Stop)
    }

    private fun finishRun(status: RunStatus, reason: String?) {
        scope.launch {
            currentRunRecord?.let { record ->
                historyRepo.updateRun(record.copy(
                    endedAt = System.currentTimeMillis(),
                    // Read from the state machine's HANG_UP-success counter,
                    // not currentCycle() — by the time finishRun runs, the
                    // state has already transitioned to Failed/Completed/
                    // StoppedByUser and currentCycle() returns 0.
                    completedCycles = stateMachine.completedCycles(),
                    status = status,
                    failureReason = reason
                ))
            }
            AutoDialAccessibilityService.instance?.endRun()
            overlayController?.dismiss()
            releaseWakeLock()
            _publicState.value = RunState.Idle
            // Intentionally do NOT startActivity(MainActivity) here. OPPO
            // ColorOS aggressively destroys MainActivity while the target app
            // is foreground, and the cold-relaunch from a stopping service
            // lands on a persistently black window. The run ended cleanly —
            // leave the user on whatever they're looking at (usually the
            // target app's dial pad). They return to AutoDial via the app
            // switcher or launcher icon, which goes through normal warm-start
            // paths and renders correctly every time.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Wake lock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ON_AFTER_RELEASE or
            PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AutoDial:run"
        ).also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AutoDial Run", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(number: String, statusText: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RunForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoDial: $number")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "STOP", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: RunState) {
        val text = when (state) {
            is RunState.EnteringNumber -> "Cycle ${state.cycle + 1} — entering number"
            is RunState.PressingCall -> "Cycle ${state.cycle + 1} — calling"
            is RunState.InCall -> "Cycle ${state.cycle + 1} — in call"
            is RunState.HangingUp -> "Hanging up"
            is RunState.Completed -> "Done"
            else -> state::class.simpleName ?: ""
        }
        val params = currentParams ?: return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(params.number, text))
    }

    private fun Intent.toRunParams(): RunParams? {
        val number = getStringExtra("number") ?: return null
        val pkg = getStringExtra("targetPackage") ?: return null
        return RunParams(
            number = number,
            targetPackage = pkg,
            plannedCycles = getIntExtra("plannedCycles", 10),
            hangupSeconds = getIntExtra("hangupSeconds", 25),
            spamModeSafetyCap = getIntExtra("spamModeSafetyCap", 9999),
            interDigitDelayMs = getLongExtra("interDigitDelayMs", 250L)
        )
    }
}
