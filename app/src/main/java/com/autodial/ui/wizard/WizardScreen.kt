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
                        "This button didn't expose a resource ID. AutoDial will rely on text + coordinates, which may be less stable if the app updates.",
                        modifier = Modifier.padding(12.dp),
                        color = YellowWarn
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (state.lastCaptured == null) {
                Button(
                    onClick = { vm.startRecording() },
                    enabled = !state.isRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (state.isRecording) "Waiting for tap…" else "Start Recording")
                }
            } else {
                Text("Captured: step '${state.lastCaptured!!.stepId}'", color = GreenOk)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.reRecord() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Re-record") }
                    Button(
                        onClick = { vm.confirmAndAdvance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.weight(1f)
                    ) { Text("Next →") }
                }
            }
        }
    }
}
