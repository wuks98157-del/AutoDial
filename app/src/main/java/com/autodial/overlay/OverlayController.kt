package com.autodial.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.autodial.model.RunState
import com.autodial.service.RunForegroundService

class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private val lifecycleOwner = SimpleLifecycleOwner()

    private var overlayX = 0
    private var overlayY = 200

    private val _state = mutableStateOf<RunState>(RunState.Idle)

    fun updateState(state: RunState) {
        _state.value = state
        val isActive = state.isActive()
        if (isActive && overlayView == null) show()
        else if (!isActive) dismiss()
    }

    private fun show() {
        lifecycleOwner.start()
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setContent {
                OverlayBubble(
                    state = _state.value,
                    onStop = {
                        context.startService(
                            android.content.Intent(context, RunForegroundService::class.java)
                                .apply { action = RunForegroundService.ACTION_STOP }
                        )
                    },
                    onDrag = { dx, dy ->
                        overlayX = (overlayX + dx.toInt())
                        overlayY = (overlayY + dy.toInt())
                        updateLayoutPosition()
                    }
                )
            }
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
            gravity = Gravity.TOP or Gravity.START
            x = overlayX; y = overlayY
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    fun dismiss() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        lifecycleOwner.stop()
    }

    private fun updateLayoutPosition() {
        val view = overlayView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = overlayX; params.y = overlayY
        windowManager.updateViewLayout(view, params)
    }

    private fun RunState.isActive(): Boolean =
        this !is RunState.Idle && this !is RunState.Completed &&
        this !is RunState.StoppedByUser && this !is RunState.Failed

    private inner class SimpleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

@Composable
private fun OverlayBubble(
    state: RunState,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val (line1, line2) = when (state) {
        is RunState.TypingDigits -> "Cycle ${state.cycle + 1}" to "Dialing ${state.params.number}"
        is RunState.InCall -> {
            val remaining = ((state.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            "Cycle ${state.cycle + 1} · ${remaining}s" to state.params.number
        }
        is RunState.HangingUp -> "Hanging up" to ""
        is RunState.ReturningToDialPad -> "Returning to dial pad" to ""
        else -> "" to ""
    }

    Box(
        Modifier
            .background(Color(0xCC_00_00_00), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 100.dp)) {
                if (line1.isNotEmpty()) Text(line1, color = Color.White, fontSize = 13.sp)
                if (line2.isNotEmpty()) Text(line2, color = Color(0xFFAAAAAA), fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text("STOP", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
