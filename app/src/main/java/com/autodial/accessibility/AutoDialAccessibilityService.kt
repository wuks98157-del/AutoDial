package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autodial.data.db.entity.RecipeStep
import com.autodial.model.RecordedStep
import com.autodial.model.RunEvent
import com.autodial.model.RunParams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AutoDialAccessibilityService : AccessibilityService() {

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
                Log.d(TAG, "WINDOW_STATE_CHANGED pkg=$pkg cls=${event.className} activeTarget=$activeTargetPackage")
                if (pkg == activeTargetPackage) {
                    scope.launch { _runEvents.emit(RunEvent.TargetForegrounded(pkg)) }
                } else if (activeTargetPackage != null) {
                    scope.launch { _runEvents.emit(RunEvent.TargetBackgrounded(activeTargetPackage!!)) }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recorder?.onEvent(event)
            else -> {}
        }
    }

    // ── Record Mode API ─────────────────────────────────────────────────────

    fun startRecording(stepId: String, targetPackage: String) {
        recorder?.startCapturing(stepId, targetPackage)
    }

    fun startRecordingSequence(
        stepIds: List<String>,
        targetPackage: String,
        digitAutoMode: Boolean = false
    ) {
        recorder?.startCapturing(stepIds, targetPackage, digitAutoMode)
    }

    fun stopRecording() {
        recorder?.stopCapturing()
    }

    fun recordedSteps() = recorder?.capturedSteps

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
