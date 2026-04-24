package com.autodial.ui.dialer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.theme.Orange
import com.autodial.ui.theme.Red
import com.autodial.ui.theme.YellowWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(
    vm: DialerViewModel = hiltViewModel(),
    onNavigateToActiveRun: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToWizard: (String) -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.isRunActive) {
        if (state.isRunActive) onNavigateToActiveRun()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoDial") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (!state.accessibilityEnabled) {
                AccessibilityBanner()
            }

            if (state.bizPhoneStale) {
                StaleBanner("BizPhone has updated — re-record recipe in Settings")
            }
            if (state.mobileVoipStale) {
                StaleBanner("Mobile VOIP has updated — re-record recipe in Settings")
            }

            OutlinedTextField(
                value = state.number,
                onValueChange = { vm.setNumber(it) },
                label = { Text("Phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            TargetToggle(
                selected = state.targetPackage,
                bizPhoneHasRecipe = state.bizPhoneRecipe != null,
                mobileVoipHasRecipe = state.mobileVoipRecipe != null,
                onSelect = { vm.setTarget(it) },
                onSetupWizard = onNavigateToWizard
            )

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Spam mode")
                Switch(checked = state.spamMode, onCheckedChange = { vm.setSpamMode(it) })
            }

            if (!state.spamMode) {
                EditableStepperRow(
                    label = "Cycles",
                    value = state.cycles,
                    onValueChange = { vm.setCycles(it) },
                    onDecrement = { vm.setCycles(state.cycles - 1) },
                    onIncrement = { vm.setCycles(state.cycles + 1) }
                )
            } else {
                Text("∞  (max ${state.spamModeSafetyCap})",
                    style = MaterialTheme.typography.bodyLarge)
            }

            EditableStepperRow(
                label = "Hang-up after (s)",
                value = state.hangupSeconds,
                onValueChange = { vm.setHangupSeconds(it) },
                onDecrement = { vm.setHangupSeconds(state.hangupSeconds - 1) },
                onIncrement = { vm.setHangupSeconds(state.hangupSeconds + 1) }
            )

            Spacer(Modifier.weight(1f))

            if (state.startBlockReason.isNotEmpty()) {
                Text(state.startBlockReason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { vm.startRun() },
                enabled = state.canStart,
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("START", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
private fun AccessibilityBanner() {
    Surface(color = Red.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
        Text(
            "Accessibility service is disabled — tap Settings to re-enable",
            modifier = Modifier.padding(12.dp),
            color = Red
        )
    }
}

@Composable
private fun StaleBanner(message: String) {
    Surface(color = YellowWarn.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
        Text(message, modifier = Modifier.padding(12.dp), color = YellowWarn)
    }
}

@Composable
private fun TargetToggle(
    selected: String,
    bizPhoneHasRecipe: Boolean,
    mobileVoipHasRecipe: Boolean,
    onSelect: (String) -> Unit,
    onSetupWizard: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "com.b3networks.bizphone" to "BizPhone",
            "finarea.MobileVoip" to "Mobile VOIP"
        ).forEach { (pkg, label) ->
            val hasRecipe = if (pkg == "com.b3networks.bizphone") bizPhoneHasRecipe else mobileVoipHasRecipe
            FilterChip(
                selected = selected == pkg,
                enabled = hasRecipe,
                onClick = { if (hasRecipe) onSelect(pkg) else onSetupWizard(pkg) },
                label = { Text(if (hasRecipe) label else "$label (setup)") }
            )
        }
    }
}

@Composable
private fun EditableStepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    // Keep a local text state so the user can type freely (including transient
    // empty/invalid values). Push to the VM only when parseable.
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrement) { Text("−") }
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new.filter { it.isDigit() }.take(4)
                    text.toIntOrNull()?.let(onValueChange)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(88.dp)
            )
            IconButton(onClick = onIncrement) { Text("+") }
        }
    }
}
