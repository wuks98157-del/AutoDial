package com.autodial.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autodial.wizard.WizardState

class WizardOverlayController(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlaySimpleLifecycleOwner? = null

    private var overlayX = 40
    private var overlayY = 140

    private val _state = mutableStateOf<WizardState>(WizardState.Idle)

    private var onUndo: () -> Unit = {}
    private var onCancel: () -> Unit = {}
    private var onReRecord: () -> Unit = {}
    private var onRetrySave: () -> Unit = {}

    fun show(
        onUndo: () -> Unit,
        onCancel: () -> Unit,
        onReRecord: () -> Unit,
        onRetrySave: () -> Unit
    ) {
        if (overlayView != null) return
        this.onUndo = onUndo
        this.onCancel = onCancel
        this.onReRecord = onReRecord
        this.onRetrySave = onRetrySave

        val owner = OverlaySimpleLifecycleOwner().also { it.start() }
        lifecycleOwner = owner

        val view = ComposeView(context)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        view.setContent {
            WizardCard(
                state = _state.value,
                onUndo = { this.onUndo() },
                onCancel = { this.onCancel() },
                onReRecord = { this.onReRecord() },
                onRetrySave = { this.onRetrySave() },
                onDrag = { dx, dy ->
                    overlayX += dx.toInt()
                    overlayY += dy.toInt()
                    updatePosition()
                }
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = overlayX; y = overlayY
        }
        wm.addView(view, params)
        overlayView = view
    }

    fun updateState(state: WizardState) {
        _state.value = state
    }

    fun dismiss() {
        val v = overlayView ?: return
        overlayView = null
        try { wm.removeView(v) } catch (_: IllegalArgumentException) {}
        lifecycleOwner?.stop()
        lifecycleOwner = null
    }

    private fun updatePosition() {
        val v = overlayView ?: return
        val p = v.layoutParams as WindowManager.LayoutParams
        p.x = overlayX; p.y = overlayY
        wm.updateViewLayout(v, p)
    }

}
