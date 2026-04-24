package com.autodial.ui.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.theme.GreenOk
import com.autodial.ui.theme.Orange
import com.autodial.ui.theme.YellowWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    targetPackage: String,
    vm: WizardViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(targetPackage) { vm.init(targetPackage) }

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("Setup ${state.displayName} (${state.stepIndex + 1} / ${state.totalSteps})")
            })
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinearProgressIndicator(
                progress = { if (state.totalSteps > 0) state.stepIndex.toFloat() / state.totalSteps else 0f },
                modifier = Modifier.fillMaxWidth()
            )

            Text(state.currentStepId, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(state.instruction, style = MaterialTheme.typography.bodyLarge)

            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            if (state.showMissingIdWarning) {
                Surface(color = YellowWarn.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
                    Text(
                        "One or more buttons didn't expose a resource ID. AutoDial will fall back to class + coordinates, which may be less stable if the app updates.",
                        modifier = Modifier.padding(12.dp),
                        color = YellowWarn
                    )
                }
            }

            state.duplicateWarning?.let { warning ->
                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
                    Text(
                        warning,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val isDigitStep = state.currentStepId == RECORD_DIGITS
            val digitsAllCaptured = isDigitStep && state.capturedDigits.size >= DIGIT_STEP_IDS.size

            if (isDigitStep && state.isRecording) {
                val captured = state.capturedDigits
                val missing = (0..9).filterNot { it in captured }
                val fallback = state.allDigitsAutoDetected == false
                val hint = when {
                    captured.isEmpty() -> "Tap any digit on the dial pad to begin."
                    fallback -> "Labels not readable — tapping ORDER matters now. Tap next digit: ${missing.firstOrNull() ?: 0}"
                    else -> "Tap the remaining digits in any order."
                }
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Captured: ${if (captured.isEmpty()) "—" else captured.sorted().joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (missing.isNotEmpty()) {
                    Text(
                        "Still need: ${missing.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { captured.size.toFloat() / DIGIT_STEP_IDS.size },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.weight(1f))

            val readyToAdvance = digitsAllCaptured || (!isDigitStep && state.lastCaptured != null)

            if (!readyToAdvance) {
                Button(
                    onClick = { vm.startRecording() },
                    enabled = !state.isRecording && !state.isAutoWiping,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        when {
                            state.isAutoWiping -> "Clearing dial pad…"
                            isDigitStep && state.isRecording -> "Waiting for digits…"
                            !isDigitStep && state.isRecording -> "Waiting for tap…"
                            else -> "Start Recording"
                        }
                    )
                }
            } else {
                Text(
                    if (isDigitStep) "✓ All 10 digits captured"
                    else "Captured: step '${state.lastCaptured!!.stepId}'",
                    color = GreenOk
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.reRecord() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Re-record") }
                    Button(
                        onClick = { vm.confirmAndAdvance() },
                        enabled = state.duplicateWarning == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.weight(1f)
                    ) { Text("Next →") }
                }
            }
        }
    }
}
