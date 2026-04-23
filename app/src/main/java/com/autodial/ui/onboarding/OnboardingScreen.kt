package com.autodial.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.theme.GreenOk
import com.autodial.ui.theme.Orange
import com.autodial.ui.theme.Red

@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.currentStep) { vm.refreshPermissions() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshPermissions() }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == OnboardingStep.DONE) {
            vm.markOnboardingComplete()
            onComplete()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state.currentStep) {
            OnboardingStep.WELCOME -> WelcomeStep { vm.advance() }
            OnboardingStep.ACCESSIBILITY -> PermissionStep(
                title = "Enable Accessibility Service",
                description = "AutoDial needs the accessibility service to tap buttons inside BizPhone and Mobile VOIP on your behalf.",
                actionLabel = "Open Accessibility Settings",
                isDone = state.accessibilityDone,
                onAction = { vm.openAccessibilitySettings() },
                onNext = { vm.refreshPermissions(); vm.advance() }
            )
            OnboardingStep.OVERLAY -> PermissionStep(
                title = "Allow Overlay",
                description = "AutoDial displays a floating bubble over the target app so you can see progress and stop the run.",
                actionLabel = "Open Overlay Settings",
                isDone = state.overlayDone,
                onAction = { vm.openOverlaySettings() },
                onNext = { vm.refreshPermissions(); vm.advance() }
            )
            OnboardingStep.NOTIFICATIONS -> PermissionStep(
                title = "Allow Notifications",
                description = "AutoDial shows a persistent notification during runs with a STOP button.",
                actionLabel = "Grant Permission",
                isDone = state.notificationsDone,
                onAction = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onNext = { vm.refreshPermissions(); vm.advance() }
            )
            OnboardingStep.BATTERY -> PermissionStep(
                title = "Disable Battery Optimization",
                description = "AutoDial must be excluded from battery optimization or the OS may kill it mid-run.",
                actionLabel = "Open Battery Settings",
                isDone = state.batteryDone,
                onAction = { vm.requestBatteryExemption() },
                onNext = { vm.refreshPermissions(); vm.advance() }
            )
            OnboardingStep.OEM_SETUP -> OemSetupStep(
                oemHelper = state.oemHelper,
                onConfirm = { vm.markOemDone(); vm.advance() }
            )
            OnboardingStep.INSTALL_APPS -> InstallAppsStep(
                bizPhoneInstalled = state.bizPhoneInstalled,
                mobileVoipInstalled = state.mobileVoipInstalled,
                onRefresh = { vm.refreshPermissions() },
                onNext = { vm.advance() }
            )
            OnboardingStep.RECORD_BIZPHONE -> RecordRecipeStep(
                appName = "BizPhone",
                description = "Now record the BizPhone recipe. The wizard will open and guide you through tapping each button.",
                onSkip = { vm.markBizPhoneRecorded() }
            )
            OnboardingStep.RECORD_MOBILE_VOIP -> RecordRecipeStep(
                appName = "Mobile VOIP",
                description = "Now record the Mobile VOIP recipe.",
                onSkip = { vm.markMobileVoipRecorded() }
            )
            OnboardingStep.DONE -> { /* navigated away in LaunchedEffect */ }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Welcome to AutoDial", style = MaterialTheme.typography.headlineLarge)
        Text(
            "AutoDial automatically places VoIP calls through BizPhone or Mobile VOIP and hangs up after a timer you set. You'll need to grant a few permissions and record how to tap buttons in each target app.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = Orange),
            modifier = Modifier.fillMaxWidth()) {
            Text("Get Started")
        }
    }
}

@Composable
private fun PermissionStep(
    title: String, description: String, actionLabel: String,
    isDone: Boolean, onAction: () -> Unit, onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyLarge)
        if (!isDone) {
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        } else {
            Text("✓ Done", color = GreenOk, style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onNext, enabled = isDone,
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            modifier = Modifier.fillMaxWidth()) {
            Text("Next")
        }
    }
}

@Composable
private fun OemSetupStep(oemHelper: com.autodial.oem.OemCompatibilityHelper, onConfirm: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${oemHelper.oem.name} Setup", style = MaterialTheme.typography.headlineMedium)
        Text("Your phone requires additional settings to keep AutoDial running reliably.",
            style = MaterialTheme.typography.bodyLarge)
        oemHelper.getRequiredSettings().forEach { s ->
            Text("• ${s.displayName}: ${s.description}", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            modifier = Modifier.fillMaxWidth()) {
            Text("I've done all of these")
        }
    }
}

@Composable
private fun InstallAppsStep(
    bizPhoneInstalled: Boolean, mobileVoipInstalled: Boolean,
    onRefresh: () -> Unit, onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Install Target Apps", style = MaterialTheme.typography.headlineMedium)
        AppInstallRow("BizPhone (com.b3networks.bizphone)", bizPhoneInstalled)
        AppInstallRow("Mobile VOIP (finarea.MobileVoip)", mobileVoipInstalled)
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        Button(onClick = onNext, enabled = bizPhoneInstalled && mobileVoipInstalled,
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            modifier = Modifier.fillMaxWidth()) {
            Text("Next")
        }
    }
}

@Composable
private fun AppInstallRow(label: String, installed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (installed) "✓" else "✗", color = if (installed) GreenOk else Red)
        Text(label)
    }
}

@Composable
private fun RecordRecipeStep(appName: String, description: String, onSkip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Record $appName Recipe", style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onSkip,
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
            modifier = Modifier.fillMaxWidth()) {
            Text("Open $appName Wizard")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip (record later in Settings)")
        }
    }
}
