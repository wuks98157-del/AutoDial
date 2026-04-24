package com.autodial.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodial.wizard.MacroStep
import com.autodial.wizard.WizardState

private val AdOrange = Color(0xFFFF6B00)
private val AdBg = Color(0xCC0D0D0D)
private val AdBorder = Color(0xFF2A2A2A)
private val AdText = Color.White
private val AdTextDim = Color(0xFFAAAAAA)
private val AdGreen = Color(0xFF34C759)
private val AdRed = Color(0xFFE53935)

@Composable
fun WizardCard(
    state: WizardState,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onReRecord: () -> Unit,
    onRetrySave: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    Column(
        Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AdBg)
            .border(1.dp, AdBorder, RoundedCornerShape(10.dp))
    ) {
        // Dedicated drag handle — a thin strip above the card content.
        // Drag events here move the whole overlay; taps elsewhere on the
        // card still reach Buttons. Same pattern as OverlayController's
        // run bubble (drag on text column, buttons in a gesture-clean zone).
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, d -> onDrag(d.x, d.y) }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AdBorder)
            )
        }
        Box(Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp).padding(top = 10.dp)) {
            when (state) {
                is WizardState.Step -> StepBody(state, onUndo, onCancel)
                is WizardState.AwaitingReturn -> AwaitingBody(onCancel)
                is WizardState.DuplicateWarning -> DuplicateBody(state, onReRecord, onCancel)
                is WizardState.Completed -> CompletedBody(state, onRetrySave, onCancel)
                WizardState.Cancelled -> Text("Cancelled", color = AdText)
                WizardState.Idle -> {}
            }
        }
    }
}

@Composable
private fun StepBody(state: WizardState.Step, onUndo: () -> Unit, onCancel: () -> Unit) {
    val macroIndex = state.macro.ordinal + 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "STEP $macroIndex / 4",
                color = AdOrange, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
            Text(
                "✕", color = AdTextDim, fontSize = 14.sp,
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "Cancel wizard" }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
            )
        }
        Text(
            text = promptFor(state),
            color = AdText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        if (state.macro == MacroStep.RECORD_DIGITS) {
            DigitChips(state.captured.keys)
        }
        if (state.lastCapture != null) {
            Text("✓ ${state.lastCapture.stepId}", color = AdGreen, fontSize = 10.sp)
        }
        if (state.undoStack.isNotEmpty()) {
            Button(
                onClick = onUndo,
                colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("↶ Undo", color = AdText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DigitChips(captured: Set<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (0..9).forEach { d ->
            val done = "DIGIT_$d" in captured
            Text(
                text = "$d",
                color = if (done) AdGreen else AdTextDim,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(width = 16.dp, height = 16.dp)
                    .background(
                        if (done) AdGreen.copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
private fun AwaitingBody(onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Return to target app",
            color = AdOrange, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
        )
        Text(
            "The wizard will resume when you come back.",
            color = AdText, fontSize = 11.sp
        )
        Text(
            "✕ Cancel", color = AdTextDim, fontSize = 10.sp,
            modifier = Modifier
                .semantics { contentDescription = "Cancel wizard" }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onCancel() })
                }
        )
    }
}

@Composable
private fun DuplicateBody(
    state: WizardState.DuplicateWarning,
    onReRecord: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "⚠ Duplicate capture",
            color = AdRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
        )
        Text(state.message, color = AdText, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onReRecord,
                colors = ButtonDefaults.buttonColors(containerColor = AdOrange),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Re-record", color = Color.Black, fontSize = 11.sp)
            }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Cancel", color = AdText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CompletedBody(
    state: WizardState.Completed,
    onRetrySave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state.recipeSaved) {
            null -> Text("Saving…", color = AdOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            true -> Text("✓ Recipe saved", color = AdGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            false -> {
                Text("✕ Save failed", color = AdRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (state.error != null) Text(state.error, color = AdTextDim, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onRetrySave,
                        colors = ButtonDefaults.buttonColors(containerColor = AdOrange),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("Retry", color = Color.Black, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = AdBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("Dismiss", color = AdText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun promptFor(state: WizardState.Step): String = when (state.macro) {
    MacroStep.OPEN_DIAL_PAD -> "Tap the dial pad / keypad icon."
    MacroStep.RECORD_DIGITS -> "Tap digits 0, 1, 2, … 9 in order."
    MacroStep.CLEAR_DIGITS -> "Tap backspace / clear once. AutoDial will auto-wipe after."
    MacroStep.PRESS_CALL -> "Turn on AIRPLANE MODE first, then tap the call bar."
}
