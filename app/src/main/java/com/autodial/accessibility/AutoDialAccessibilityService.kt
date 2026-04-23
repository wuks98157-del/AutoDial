package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.DisplayMetrics
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

    // ── Self-check ──────────────────────────────────────────────────────────

    fun isBound(): Boolean = instance != null

    fun selfCheck(): Boolean {
        if (instance == null) return false
        return try {
            serviceInfo != null
        } catch (e: Exception) {
            false
        }
    }
}
