package com.autodial.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autodial.ui.common.*
import com.autodial.ui.theme.*

@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel = hiltViewModel(),
    onComplete: () -> Unit,
    onOpenWizard: (String) -> Unit = {}
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.currentStep) { vm.refreshPermissions() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshPermissions() }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == OnboardingStep.DONE) {
            vm.markOnboardingComplete()
            onComplete()
        }
    }

    val totalSteps = OnboardingStep.values().size - 2  // exclude WELCOME + DONE
    val stepIndex = state.currentStep.ordinal.coerceAtMost(totalSteps)
    val showProgress = state.currentStep != OnboardingStep.WELCOME &&
                       state.currentStep != OnboardingStep.DONE

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AdHeader(
                title = {
                    Row {
                        Text("Auto", color = OnSurfaceDark, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text("Dial", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                },
                right = {
                    if (showProgress) {
                        Text(
                            "$stepIndex / $totalSteps",
                            color = OnSurfaceVariantDark,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            fontFamily = MonoFamily,
                        )
                    }
                }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                when (state.currentStep) {
                    OnboardingStep.WELCOME -> WelcomeStep { vm.advance() }
                    OnboardingStep.ACCESSIBILITY -> PermissionStep(
                        title = "Accessibility service",
                        description = "AutoDial needs the accessibility service to tap buttons inside BizPhone and Mobile VOIP on your behalf.",
                        actionLabel = "Open accessibility settings",
                        isDone = state.accessibilityDone,
                        onAction = { vm.openAccessibilitySettings() },
                        onNext = { vm.refreshPermissions(); vm.advance() }
                    )
                    OnboardingStep.OVERLAY -> PermissionStep(
                        title = "Overlay permission",
                        description = "AutoDial shows a floating card over the target app to guide setup and show run progress.",
                        actionLabel = "Open overlay settings",
                        isDone = state.overlayDone,
                        onAction = { vm.openOverlaySettings() },
                        onNext = { vm.refreshPermissions(); vm.advance() }
                    )
                    OnboardingStep.NOTIFICATIONS -> PermissionStep(
                        title = "Notifications",
                        description = "AutoDial shows a persistent notification during runs with a STOP action.",
                        actionLabel = "Grant permission",
                        isDone = state.notificationsDone,
                        onAction = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onNext = { vm.refreshPermissions(); vm.advance() }
                    )
                    OnboardingStep.BATTERY -> PermissionStep(
                        title = "Battery optimization",
                        description = "Exempt AutoDial from battery optimization so the OS doesn't kill it mid-run.",
                        actionLabel = "Open battery settings",
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
                        description = "The overlay wizard will guide you through 4 taps inside BizPhone.",
                        recorded = state.bizPhoneRecipeRecorded,
                        onOpenWizard = { onOpenWizard("com.b3networks.bizphone") },
                        onSkipOrNext = { vm.markBizPhoneRecorded() }
                    )
                    OnboardingStep.RECORD_MOBILE_VOIP -> RecordRecipeStep(
                        appName = "Mobile VOIP",
                        description = "Same flow as BizPhone — 4 taps inside Mobile VOIP.",
                        recorded = state.mobileVoipRecipeRecorded,
                        onOpenWizard = { onOpenWizard("finarea.MobileVoip") },
                        onSkipOrNext = { vm.markMobileVoipRecorded() }
                    )
                    OnboardingStep.DONE -> { /* navigated away in LaunchedEffect */ }
                }
            }
        }
    }
}

@Composable
private fun StepHeadline(text: String) {
    AdLabel("Setup")
    Spacer(Modifier.height(6.dp))
    Text(text, color = OnSurfaceDark, fontSize = 28.sp, fontWeight = FontWeight.Black,
        letterSpacing = (-0.3).sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun StepDescription(text: String) {
    Text(text, color = OnSurfaceVariantDark, fontSize = 15.sp, fontWeight = FontWeight.Normal,
        lineHeight = 22.sp)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Spacer(Modifier.height(36.dp))
    AdLabel("Welcome")
    Spacer(Modifier.height(8.dp))
    Text("AutoDial",
        color = OnSurfaceDark, fontSize = 44.sp, fontWeight = FontWeight.Black,
        letterSpacing = (-0.7).sp)
    Spacer(Modifier.height(16.dp))
    Text(
        "Automate repeated VoIP calls through BizPhone or Mobile VOIP. Enter a number, pick a hang-up timer, tap START. AutoDial drives the target app's dial pad for you.",
        color = OnSurfaceVariantDark, fontSize = 15.sp, lineHeight = 22.sp
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "A one-time setup grants the needed permissions and records how each target app's buttons work. Takes ~3 minutes.",
        color = OnSurfaceVariantDark, fontSize = 15.sp, lineHeight = 22.sp
    )
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(32.dp))
    AdBigButton(text = "Get started", onClick = onNext)
}

@Composable
private fun PermissionStep(
    title: String, description: String, actionLabel: String,
    isDone: Boolean, onAction: () -> Unit, onNext: () -> Unit
) {
    StepHeadline(title)
    StepDescription(description)
    if (isDone) {
        StatusCard(text = "Granted", color = GreenOk)
    } else {
        OutlinedActionButton(text = actionLabel, onClick = onAction)
    }
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(24.dp))
    AdBigButton(text = "Next", onClick = onNext, enabled = isDone)
}

@Composable
private fun OemSetupStep(oemHelper: com.autodial.oem.OemCompatibilityHelper, onConfirm: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    StepHeadline("${oemHelper.oem.name} tweaks")
    StepDescription("Your phone needs extra settings tweaks so AutoDial can run reliably in the background. Open each item and follow the prompt, then confirm below.")
    oemHelper.getRequiredSettings().forEach { s ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(s.displayName, color = Orange, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
            Text(s.description, color = OnSurfaceVariantDark, fontSize = 13.sp, lineHeight = 18.sp)
            if (s.deepLinkIntent != null) {
                OutlinedActionButton(
                    text = "Open ${s.displayName}",
                    onClick = {
                        try { context.startActivity(s.deepLinkIntent) }
                        catch (e: Exception) { /* some deep links fail on newer OS builds */ }
                    }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(20.dp))
    AdBigButton(text = "I've done all of these", onClick = onConfirm)
}

@Composable
private fun InstallAppsStep(
    bizPhoneInstalled: Boolean, mobileVoipInstalled: Boolean,
    onRefresh: () -> Unit, onNext: () -> Unit
) {
    StepHeadline("Install target apps")
    StepDescription("AutoDial doesn't place calls itself — it drives BizPhone or Mobile VOIP. Install at least one before continuing.")
    AppInstallRow("BizPhone", "com.b3networks.bizphone", bizPhoneInstalled)
    Spacer(Modifier.height(8.dp))
    AppInstallRow("Mobile VOIP", "finarea.MobileVoip", mobileVoipInstalled)
    Spacer(Modifier.height(14.dp))
    OutlinedActionButton(text = "Check again", onClick = onRefresh)
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(20.dp))
    AdBigButton(
        text = "Next",
        onClick = onNext,
        enabled = bizPhoneInstalled || mobileVoipInstalled,
    )
}

@Composable
private fun AppInstallRow(name: String, packageId: String, installed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdStatusDot(if (installed) GreenOk else Red, size = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(name, color = OnSurfaceDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(packageId, color = OnSurfaceMuteDark, fontSize = 11.sp,
                fontFamily = MonoFamily, letterSpacing = 0.4.sp)
        }
        Text(
            if (installed) "INSTALLED" else "NOT FOUND",
            color = if (installed) GreenOk else Red,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun RecordRecipeStep(
    appName: String,
    description: String,
    recorded: Boolean,
    onOpenWizard: () -> Unit,
    onSkipOrNext: () -> Unit
) {
    StepHeadline("Record $appName")
    StepDescription(description)
    if (recorded) {
        StatusCard(text = "Recipe recorded", color = GreenOk)
        Spacer(Modifier.height(12.dp))
        OutlinedActionButton(text = "Re-record $appName", onClick = onOpenWizard)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(20.dp))
        AdBigButton(text = "Next", onClick = onSkipOrNext)
    } else {
        AdBigButton(text = "Open $appName wizard", onClick = onOpenWizard)
        Spacer(Modifier.height(14.dp))
        TextAction(text = "Skip — record later in Settings", onClick = onSkipOrNext)
    }
}

@Composable
private fun StatusCard(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("✓", color = color, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(text.uppercase(), color = color, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun OutlinedActionButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(10.dp),
        border = ButtonDefaults.outlinedButtonBorder(true)
            .copy(width = 1.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Orange,
            containerColor = Color.Transparent,
        ),
    ) {
        Text(text.uppercase(), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp)
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = OnSurfaceVariantDark, fontSize = 13.sp)
    }
}
