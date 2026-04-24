package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autodial.data.db.entity.RecipeStep
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.RecordedStep
import com.autodial.model.RunEvent
import com.autodial.model.RunParams
import com.autodial.overlay.WizardOverlayController
import com.autodial.wizard.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@AndroidEntryPoint
class AutoDialAccessibilityService : AccessibilityService() {

    @Inject lateinit var recipeRepo: RecipeRepository

    companion object {
        @Volatile var instance: AutoDialAccessibilityService? = null
            private set
        private const val TAG = "AutoDial"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _runEvents = MutableSharedFlow<RunEvent>(extraBufferCapacity = 64)
    val runEvents: SharedFlow<RunEvent> = _runEvents.asSharedFlow()

    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0

    private var recorder: UiRecorder? = null
    private var player: UiPlayer? = null

    @Volatile private var activeTargetPackage: String? = null

    private var wizardStateMachine: WizardStateMachine? = null
    private var wizardOverlay: WizardOverlayController? = null
    private var recipeWriter: WizardRecipeWriter? = null
    @Volatile private var wizardActivePackage: String? = null
    private var wizardJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi

        recorder = UiRecorder(this, screenW, screenH, densityDpi)
        player = UiPlayer(this, screenW, screenH)
    }

    override fun onDestroy() {
        wizardStateMachine?.onEvent(WizardEvent.ServiceRevoked)
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ── Accessibility Events ───────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                Log.d(TAG, "WINDOW_STATE_CHANGED pkg=$pkg cls=${event.className} activeTarget=$activeTargetPackage wizardActive=$wizardActivePackage")
                if (pkg == activeTargetPackage) {
                    scope.launch { _runEvents.emit(RunEvent.TargetForegrounded(pkg)) }
                } else if (activeTargetPackage != null) {
                    scope.launch { _runEvents.emit(RunEvent.TargetBackgrounded(activeTargetPackage!!)) }
                }
                val wizSm = wizardStateMachine
                val wizPkg = wizardActivePackage
                if (wizSm != null && wizPkg != null) {
                    if (pkg == wizPkg) wizSm.onEvent(WizardEvent.TargetForegrounded)
                    else wizSm.onEvent(WizardEvent.TargetBackgrounded)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recorder?.onEvent(event)
            else -> {}
        }
    }

    // ── Play Mode API ───────────────────────────────────────────────────────

    fun beginRun(params: RunParams) {
        activeTargetPackage = params.targetPackage
    }

    fun endRun() {
        activeTargetPackage = null
    }

    suspend fun executeStep(step: RecipeStep): StepOutcome {
        return player?.executeStep(step)
            ?: StepOutcome.Failed("failed:service-not-ready")
    }

    suspend fun longPressStep(step: RecipeStep, durationMs: Long): StepOutcome {
        return player?.longPressStep(step, durationMs)
            ?: StepOutcome.Failed("failed:service-not-ready")
    }

    suspend fun tapByResourceId(resourceId: String): StepOutcome {
        return player?.tapByResourceId(resourceId)
            ?: StepOutcome.Failed("failed:service-not-ready")
    }

    // Exposed so the run service can poll the live accessibility tree while
    // waiting for a recorded node to become reachable (splash → real UI).
    fun rootNode(): android.view.accessibility.AccessibilityNodeInfo? = rootInActiveWindow


    // ── Wizard Mode API ─────────────────────────────────────────────────────

    fun beginWizard(targetPackage: String) {
        if (wizardStateMachine != null) return
        val sm = WizardStateMachine()
        val overlay = WizardOverlayController(this)
        val writer = WizardRecipeWriter(applicationContext, recipeRepo)
        wizardStateMachine = sm
        wizardOverlay = overlay
        recipeWriter = writer
        wizardActivePackage = targetPackage

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null) applicationContext.startActivity(launchIntent)

        overlay.show(
            onUndo = { sm.onCommand(WizardCommand.Undo) },
            onCancel = { sm.onCommand(WizardCommand.Cancel) },
            onReRecord = { sm.onCommand(WizardCommand.ReRecord) },
            onRetrySave = { sm.onCommand(WizardCommand.RetrySave) }
        )
        sm.onCommand(WizardCommand.Start(targetPackage))

        wizardJob = scope.launch {
            // Collect captures from UiRecorder into the state machine.
            launch {
                recorder?.capturedSteps?.collect { recorded ->
                    sm.onEvent(WizardEvent.Captured(recorded))
                }
            }
            // Subscribe to state flow: re-render overlay, re-arm UiRecorder, dismiss on terminal.
            launch {
                sm.state.collect { state ->
                    overlay.updateState(state)
                    when (state) {
                        is WizardState.Step ->
                            recorder?.startCapturing(state.queue, state.targetPackage)
                        is WizardState.AwaitingReturn ->
                            recorder?.stopCapturing()
                        is WizardState.Completed -> {
                            if (state.recipeSaved != null) {
                                if (state.recipeSaved) kotlinx.coroutines.delay(2000L)
                                endWizard()
                            }
                            // Completed(recipeSaved = null) is transient "saving" — wait for
                            // RecipeSaveResult to flip us to Completed(true/false) before tearing down.
                        }
                        WizardState.Cancelled -> endWizard()
                        else -> {}
                    }
                }
            }
            // Subscribe to side-effect channel: autoWipe + save.
            launch {
                sm.sideEffects.collect { effect ->
                    when (effect) {
                        is WizardSideEffect.AutoWipeDialPad ->
                            autoWipeDialPad(effect.clearStep, effect.targetPackage)
                        is WizardSideEffect.SaveRecipe ->
                            runSave(writer, effect.targetPackage, effect.captured, sm)
                    }
                }
            }
        }
    }

    fun endWizard() {
        wizardJob?.cancel()
        wizardJob = null
        wizardOverlay?.dismiss()
        wizardOverlay = null
        wizardStateMachine = null
        recipeWriter = null
        wizardActivePackage = null
        recorder?.stopCapturing()
    }

    private suspend fun runSave(
        writer: WizardRecipeWriter,
        targetPackage: String,
        captured: Map<String, com.autodial.model.RecordedStep>,
        sm: WizardStateMachine
    ) {
        try {
            writer.save(targetPackage, captured)
            sm.onEvent(WizardEvent.RecipeSaveResult(success = true))
        } catch (e: Exception) {
            sm.onEvent(WizardEvent.RecipeSaveResult(success = false, error = e.message))
        }
    }

    // Taps the recorded CLEAR_DIGITS node ~15× to empty the dial pad after the
    // user's single capture-tap. Same logic as the old WizardViewModel.autoWipeDialPad
    // — moved here because the service now owns wizard side-effects.
    //
    // Critical: the state-collector already armed UiRecorder for [PRESS_CALL]
    // the instant we entered the PRESS_CALL macro. If we didn't pause it here,
    // our own programmatic backspace taps would fire accessibility click
    // events in the target app and UiRecorder would consume the PRESS_CALL
    // slot — advancing the wizard to Completed without ever asking the user
    // to tap the real call button. Stop the recorder for the duration of
    // the wipe, then re-arm with whatever the state machine has queued next.
    private suspend fun autoWipeDialPad(
        clear: com.autodial.model.RecordedStep,
        targetPackage: String
    ) {
        recorder?.stopCapturing()
        val step = com.autodial.data.db.entity.RecipeStep(
            targetPackage = targetPackage, stepId = "CLEAR_DIGITS",
            resourceId = clear.resourceId, text = clear.text, className = clear.className,
            boundsRelX = clear.boundsRelX, boundsRelY = clear.boundsRelY,
            boundsRelW = clear.boundsRelW, boundsRelH = clear.boundsRelH,
            screenshotHashHex = clear.screenshotHashHex,
            recordedOnDensityDpi = clear.recordedOnDensityDpi,
            recordedOnScreenW = clear.recordedOnScreenW,
            recordedOnScreenH = clear.recordedOnScreenH
        )
        val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) applicationContext.startActivity(intent)
        kotlinx.coroutines.delay(500L)
        repeat(15) {
            executeStep(step)
            kotlinx.coroutines.delay(120L)
        }
        // Re-arm the recorder for the current macro's queue — typically
        // [PRESS_CALL] after a CLEAR_DIGITS → PRESS_CALL transition.
        val cur = wizardStateMachine?.state?.value as? WizardState.Step
        if (cur != null) {
            recorder?.startCapturing(cur.queue, cur.targetPackage)
        }
    }

    // ── Self-check ──────────────────────────────────────────────────────────

    fun isBound(): Boolean = instance != null

    fun startSelfCheckLoop() {
        scope.launch {
            while (true) {
                delay(30_000L)
                if (!selfCheck()) {
                    _runEvents.emit(RunEvent.StepActionFailed("SELF_CHECK", "failed:accessibility-service-revoked"))
                    break
                }
            }
        }
    }

    fun stopSelfCheckLoop() {
        // The loop exits naturally when the scope is cancelled or selfCheck() fails
    }

    fun selfCheck(): Boolean {
        if (instance == null) return false
        return try {
            serviceInfo != null
        } catch (e: Exception) {
            false
        }
    }
}
