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
                    stateMachine.onEvent(event)
                    _publicState.value = stateMachine.state.value
                }
            }

            // Drive the state machine
            stateMachine.state.collect { state ->
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
                // Launch target app — we are foreground right now, so no BAL restriction
                val launchIntent = packageManager.getLaunchIntentForPackage(params.targetPackage)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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

            is RunState.OpeningDialPad -> executeStep("OPEN_DIAL_PAD", state.params, accessService)

            is RunState.TypingDigits -> {
                val digit = state.params.number[state.digitIndex].toString()
                executeStep("DIGIT_$digit", state.params, accessService)
                if (state.digitIndex < state.params.number.length - 1) {
                    delay(state.params.interDigitDelayMs)
                }
            }

            is RunState.PressingCall -> executeStep("PRESS_CALL", state.params, accessService)

            is RunState.InCall -> {
                val remaining = state.hangupAt - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                val steps = recipeRepo.getSteps(state.params.targetPackage)
                // After hangupSeconds the call should be connected; prefer HANG_UP_CONNECTED.
                // Fall back to HANG_UP_RINGING only if no connected-hangup step was recorded.
                val hangupStep = if (steps.any { it.stepId == "HANG_UP_CONNECTED" }) {
                    "HANG_UP_CONNECTED"
                } else {
                    "HANG_UP_RINGING"
                }
                stateMachine.onEvent(RunEvent.StepActionSucceeded("PRESS_CALL", "ok:timer-elapsed"))
                executeStep(hangupStep, state.params, accessService)
            }

            is RunState.HangingUp -> { /* driven by IN_CALL above */ }

            is RunState.ReturningToDialPad -> {
                delay(800L) // inter-cycle settle
                executeStep("RETURN_TO_DIAL_PAD", state.params, accessService)
            }

            is RunState.Completed -> finishRun(RunStatus.DONE, null)
            is RunState.StoppedByUser -> finishRun(RunStatus.STOPPED, null)
            is RunState.Failed -> finishRun(RunStatus.FAILED, state.reason)
            else -> {}
        }
    }

    private suspend fun executeStep(
        stepId: String,
        params: RunParams,
        accessService: AutoDialAccessibilityService
    ) {
        val steps = recipeRepo.getSteps(params.targetPackage)
        val step = steps.firstOrNull { it.stepId == stepId }
        if (step == null) {
            stateMachine.onEvent(RunEvent.StepActionFailed(stepId, "failed:step-not-recorded"))
            return
        }
        val outcome = accessService.executeStep(step)
        val outStr = when (outcome) {
            is StepOutcome.Ok -> outcome.outcome
            is StepOutcome.Failed -> outcome.reason
        }
        historyRepo.logStepEvent(RunStepEvent(
            runId = runId, cycleIndex = currentCycle(), stepId = stepId,
            at = System.currentTimeMillis(), outcome = outStr, detail = null
        ))
        when (outcome) {
            is StepOutcome.Ok -> stateMachine.onEvent(RunEvent.StepActionSucceeded(stepId, outStr))
            is StepOutcome.Failed -> stateMachine.onEvent(RunEvent.StepActionFailed(stepId, outStr))
        }
    }

    private fun currentCycle(): Int {
        return when (val s = stateMachine.state.value) {
            is RunState.TypingDigits -> s.cycle
            is RunState.PressingCall -> s.cycle
            is RunState.InCall -> s.cycle
            is RunState.HangingUp -> s.cycle
            is RunState.ReturningToDialPad -> s.cycle
            else -> 0
        }
    }

    private fun stopRun() {
        stateMachine.onCommand(RunCommand.Stop)
    }

    private fun finishRun(status: RunStatus, reason: String?) {
        scope.launch {
            currentRunRecord?.let { record ->
                historyRepo.updateRun(record.copy(
                    endedAt = System.currentTimeMillis(),
                    completedCycles = currentCycle(),
                    status = status,
                    failureReason = reason
                ))
            }
            AutoDialAccessibilityService.instance?.endRun()
            overlayController?.dismiss()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _publicState.value = RunState.Idle
            // Bring AutoDial back to foreground
            val intent = Intent(this@RunForegroundService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
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
            is RunState.TypingDigits -> "Cycle ${state.cycle + 1} — dialing"
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
            interDigitDelayMs = getLongExtra("interDigitDelayMs", 400L)
        )
    }
}
