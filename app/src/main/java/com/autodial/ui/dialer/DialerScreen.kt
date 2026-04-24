package com.autodial.ui.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.common.*
import com.autodial.ui.theme.*

@Composable
fun DialerScreen(
    vm: DialerViewModel = hiltViewModel(),
    onNavigateToActiveRun: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onBeginWizard: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.isRunActive) { if (state.isRunActive) onNavigateToActiveRun() }

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
                left = {
                    AdIconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                },
                right = {
                    AdIconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Status row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dotColor = if (state.accessibilityEnabled) GreenOk else Red
                    AdStatusDot(dotColor)
                    AdLabel(if (state.accessibilityEnabled) "Ready" else "Accessibility off")
                }

                if (!state.accessibilityEnabled) {
                    StaleBanner("Accessibility service disabled — tap Settings to re-enable", Red)
                }
                if (state.bizPhoneStale) {
                    StaleBanner("BizPhone has updated — re-record recipe in Settings", YellowWarn)
                }
                if (state.mobileVoipStale) {
                    StaleBanner("Mobile VOIP has updated — re-record recipe in Settings", YellowWarn)
                }

                PhoneNumberField(state.number, onChange = vm::setNumber)

                if (state.recentNumbers.isNotEmpty()) {
                    RecentNumbersRow(
                        numbers = state.recentNumbers,
                        current = state.number,
                        onPick = vm::setNumber,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.spamMode) {
                        Box(Modifier.weight(1f)) {
                            EditableField(
                                label = "Cycles",
                                value = state.cycles.toString(),
                                unit = "×",
                                onValueChange = { it.toIntOrNull()?.let(vm::setCycles) }
                            )
                        }
                    } else {
                        AdSmallField(
                            label = "Cycles",
                            value = "∞",
                            unit = "max ${state.spamModeSafetyCap}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        EditableField(
                            label = "Hang-up after",
                            value = state.hangupSeconds.toString(),
                            unit = "sec",
                            onValueChange = { it.toIntOrNull()?.let(vm::setHangupSeconds) }
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdLabel("Target app")
                    TargetToggle(
                        selected = state.targetPackage,
                        bizPhoneHasRecipe = state.bizPhoneRecipe != null,
                        mobileVoipHasRecipe = state.mobileVoipRecipe != null,
                        onSelect = vm::setTarget,
                        onSetupWizard = onBeginWizard,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Spam mode", color = OnSurfaceDark, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.spamMode,
                        onCheckedChange = vm::setSpamMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Orange,
                            uncheckedTrackColor = SurfaceVariantDark,
                            uncheckedThumbColor = OnSurfaceVariantDark,
                            uncheckedBorderColor = BorderDark,
                        )
                    )
                }

                Spacer(Modifier.weight(1f))

                if (state.startBlockReason.isNotEmpty()) {
                    Text(state.startBlockReason, color = Red, style = MaterialTheme.typography.bodyMedium)
                }

                EstTotalStrip(
                    cycles = state.cycles,
                    hangupSec = state.hangupSeconds,
                    spam = state.spamMode,
                )

                AdBigButton(
                    text = "Start",
                    onClick = vm::startRun,
                    enabled = state.canStart,
                    leading = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black) }
                )
            }
        }
    }
}

@Composable
private fun PhoneNumberField(value: String, onChange: (String) -> Unit) {
    // BasicTextField provides the actual input surface. The AdBigField styling
    // above supplies the label + hint + mono display. We paint BasicTextField
    // in the same mono style so the user's typed characters render inline.
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(2.dp, Orange, RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        AdLabel("Phone number")
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.filter(Char::isDigit).take(15)) },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = OnSurfaceDark,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
                letterSpacing = 0.6.sp
            ),
            cursorBrush = SolidColor(Orange),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "NUMERIC KEYPAD · INTL FORMAT OK",
            color = OnSurfaceMuteDark,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun EditableField(
    label: String,
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(
        Modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        AdLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    text = new.filter(Char::isDigit).take(4)
                    onValueChange(text)
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = OnSurfaceDark,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFamily,
                ),
                cursorBrush = SolidColor(Orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                unit.uppercase(),
                color = OnSurfaceMuteDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun StaleBanner(message: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
        Text(message, modifier = Modifier.padding(12.dp), color = tint)
    }
}

@Composable
private fun RecentNumbersRow(
    numbers: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AdLabel("Recent")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            numbers.forEach { n ->
                val active = n == current
                Box(
                    Modifier
                        .background(
                            if (active) Orange else SurfaceDark,
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (active) Orange else BorderDark,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onPick(n) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        n,
                        color = if (active) Color.Black else OnSurfaceDark,
                        fontFamily = MonoFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetToggle(
    selected: String,
    bizPhoneHasRecipe: Boolean,
    mobileVoipHasRecipe: Boolean,
    onSelect: (String) -> Unit,
    onSetupWizard: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(14.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            "com.b3networks.bizphone" to "BizPhone",
            "finarea.MobileVoip" to "Mobile VOIP"
        ).forEach { (pkg, label) ->
            val hasRecipe = if (pkg == "com.b3networks.bizphone") bizPhoneHasRecipe else mobileVoipHasRecipe
            val active = selected == pkg
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .background(
                        if (active) Orange else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { if (hasRecipe) onSelect(pkg) else onSetupWizard(pkg) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AdStatusDot(if (active) Color.Black else OnSurfaceMuteDark)
                    Text(
                        if (hasRecipe) label.uppercase() else "${label.uppercase()} (SETUP)",
                        color = if (active) Color.Black else OnSurfaceVariantDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EstTotalStrip(cycles: Int, hangupSec: Int, spam: Boolean) {
    val totalSec = if (spam) -1 else cycles * hangupSec
    val formatted = when {
        totalSec < 0 -> "— indefinite —"
        totalSec >= 60 -> "≈ ${totalSec / 60} min ${totalSec % 60} sec"
        else -> "≈ $totalSec sec"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AdLabel("Est. total")
        Text(
            formatted,
            color = OnSurfaceDark,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFamily,
            fontSize = 13.sp,
        )
    }
}
