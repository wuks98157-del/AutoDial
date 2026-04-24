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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autodial.model.RunState
import com.autodial.model.isActive
import com.autodial.service.RunForegroundService
import kotlinx.coroutines.delay

class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null

    // Nullable var so a fresh instance is created on each show() call (Fix 2)
    private var lifecycleOwner: OverlaySimpleLifecycleOwner? = null

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
        // Fix 2 — create a fresh owner each time so LifecycleRegistry is never reused
        val owner = OverlaySimpleLifecycleOwner().also { it.start() }
        lifecycleOwner = owner

        val view = ComposeView(context)

        // Fix 1 — set ViewTree owners before setContent is called
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)

        view.apply {
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
        // Fix 2 + Fix 3 — guard against dismiss() before show() (lifecycleOwner is null)
        // Fix double removeView race — null overlayView before removeView, catch IllegalArgumentException
        val view = overlayView ?: return
        overlayView = null
        try { windowManager.removeView(view) } catch (_: IllegalArgumentException) {}
        lifecycleOwner?.stop()
        lifecycleOwner = null
    }

    private fun updateLayoutPosition() {
        val view = overlayView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = overlayX; params.y = overlayY
        windowManager.updateViewLayout(view, params)
    }

    // Removed duplicate private RunState.isActive() extension — the public one from RunState.kt
    // is used directly in updateState() above.
}

// Fix 4 — ticking helper composable so InCall countdown updates every second
@Composable
private fun rememberInCallText(state: RunState.InCall): Pair<String, String> {
    var remaining by remember(state.hangupAt) {
        mutableStateOf(((state.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(state.hangupAt) {
        while (remaining > 0L) {
            delay(1_000L)
            remaining = ((state.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        }
    }
    return "Cycle ${state.cycle + 1} · ${remaining}s" to state.params.number
}

@Composable
private fun OverlayBubble(
    state: RunState,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    // Removed unused offsetX / offsetY vars

    // Fix 4 — call rememberInCallText() outside the when expression (LaunchedEffect needs
    // a stable composable call site, not one buried inside a branch)
    val inCallText: Pair<String, String>? = if (state is RunState.InCall) rememberInCallText(state) else null

    val (line1, line2) = when (state) {
        is RunState.EnteringNumber -> "Cycle ${state.cycle + 1}" to "Dialing ${state.params.number}"
        is RunState.PressingCall -> "Cycle ${state.cycle + 1}" to "Calling ${state.params.number}"
        is RunState.InCall -> inCallText!!
        is RunState.HangingUp -> "Hanging up" to ""
        else -> "" to ""
    }

    // The drag detector lives on the left text column ONLY — if it wraps the
    // whole bubble, Compose's detectDragGestures consumes events during its
    // touch-slop-wait phase and the STOP button never sees taps. (That's why
    // spam-tapping eventually worked; a perfectly still tap sneaks through
    // the slop window.) Keeping the button in a gesture-clean zone makes STOP
    // a single-tap every time.
    Box(
        Modifier
            .background(Color(0xCC_00_00_00), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .widthIn(min = 100.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
            ) {
                if (line1.isNotEmpty()) Text(line1, color = Color.White, fontSize = 13.sp)
                if (line2.isNotEmpty()) Text(line2, color = Color(0xFFAAAAAA), fontSize = 11.sp)
                // Make the drag column always have some height even when text lines are empty,
                // so the user has a draggable surface.
                if (line1.isEmpty() && line2.isEmpty()) Spacer(Modifier.height(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            ) {
                Text("STOP", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
