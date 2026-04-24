package com.autodial.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.common.AdHeader
import com.autodial.ui.common.AdIconButton
import com.autodial.ui.common.AdLabel
import com.autodial.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
    onNavigateToWizard: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val settings = state.settings ?: return

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AdHeader(
                title = {
                    Text(
                        "SETTINGS",
                        color = OnSurfaceDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.4.sp
                    )
                },
                left = {
                    AdIconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                // ── Defaults ────────────────────────────────────────────
                AdSection(label = "Defaults") {
                    NumberSetting(
                        label = "Default hang-up (s)",
                        value = settings.defaultHangupSeconds,
                        min = 1, max = 600,
                        onSet = { vm.setDefaultHangup(it) }
                    )
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    NumberSetting(
                        label = "Default cycles",
                        value = settings.defaultCycles,
                        min = 1, max = 9999,
                        onSet = { vm.setDefaultCycles(it) }
                    )
                }

                // ── Advanced ─────────────────────────────────────────────
                AdSection(label = "Advanced") {
                    NumberSetting(
                        label = "Spam mode safety cap",
                        value = settings.spamModeSafetyCap,
                        min = 1, max = 99999,
                        onSet = { vm.setSpamCap(it) }
                    )
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    NumberSetting(
                        label = "Inter-digit delay (ms)",
                        value = settings.interDigitDelayMs,
                        min = 100, max = 2000,
                        onSet = { vm.setInterDigitDelay(it) }
                    )
                }

                // ── Recipes ───────────────────────────────────────────────
                AdSection(label = "Recipes") {
                    RecipeRow(
                        name = "BizPhone",
                        recordedAt = state.bizPhoneRecordedAt,
                        version = state.bizPhoneVersion,
                        onReRecord = { onNavigateToWizard("com.b3networks.bizphone") }
                    )
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    RecipeRow(
                        name = "Mobile VOIP",
                        recordedAt = state.mobileVoipRecordedAt,
                        version = state.mobileVoipVersion,
                        onReRecord = { onNavigateToWizard("finarea.MobileVoip") }
                    )
                }

                // ── Device ────────────────────────────────────────────────
                AdSection(label = "Device") {
                    PermRow(
                        label = "Accessibility Service",
                        ok = state.accessibilityOk,
                        deepLink = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    PermRow(
                        label = "Overlay Permission",
                        ok = state.overlayOk,
                        deepLink = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    PermRow(label = "Notifications", ok = state.notificationsOk, deepLink = null)
                    HorizontalDivider(color = BorderDark, thickness = 1.dp)
                    PermRow(
                        label = "Battery Optimization Exempt",
                        ok = state.batteryOk,
                        deepLink = {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                }

                // ── OEM Settings (conditional) ────────────────────────────
                if (vm.oemHelper.oem != com.autodial.oem.OemCompatibilityHelper.Oem.GENERIC) {
                    AdSection(label = "OEM Settings (${vm.oemHelper.oem.name})") {
                        val oemSettings = vm.oemHelper.getRequiredSettings()
                        oemSettings.forEachIndexed { index, setting ->
                            OemSettingRow(setting, onClick = {
                                setting.deepLinkIntent?.let { context.startActivity(it) }
                            })
                            if (index < oemSettings.lastIndex) {
                                HorizontalDivider(color = BorderDark, thickness = 1.dp)
                            }
                        }
                    }
                }

                // ── About ─────────────────────────────────────────────────
                AdSection(label = "About") {
                    val versionName = remember {
                        try {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0).versionName ?: "?"
                        } catch (_: Exception) { "?" }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.tapVersion() }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "BUILD $versionName",
                            color = OnSurfaceMuteDark,
                            fontFamily = MonoFamily,
                            fontSize = 11.sp,
                            letterSpacing = 0.8.sp
                        )
                    }
                    if (state.devMenuUnlocked) {
                        HorizontalDivider(color = BorderDark, thickness = 1.dp)
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                            AdLabel("Dev Menu", color = Orange)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { onNavigateToWizard("com.b3networks.bizphone") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Orange)
                            ) {
                                Text("Force re-record BizPhone")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onNavigateToWizard("finarea.MobileVoip") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Orange)
                            ) {
                                Text("Force re-record Mobile VOIP")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Private section wrapper ────────────────────────────────────────────────────

@Composable
private fun AdSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    AdLabel(
        text = label,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .border(1.dp, BorderDark),
        content = content
    )
}

// ── Private row composables ────────────────────────────────────────────────────

@Composable
private fun NumberSetting(label: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AdLabel(
            text = label,
            modifier = Modifier.weight(1f),
            color = OnSurfaceVariantDark
        )
        IconButton(onClick = { if (value > min) onSet(value - 1) }) {
            Text("−", color = OnSurfaceDark, fontSize = 18.sp)
        }
        Text(
            text = "$value",
            modifier = Modifier.width(48.dp),
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = OnSurfaceDark,
            textAlign = TextAlign.Center
        )
        IconButton(onClick = { if (value < max) onSet(value + 1) }) {
            Text("+", color = OnSurfaceDark, fontSize = 18.sp)
        }
    }
}

@Composable
private fun RecipeRow(name: String, recordedAt: Long?, version: String?, onReRecord: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            AdLabel(text = name, color = OnSurfaceVariantDark)
            Spacer(Modifier.height(4.dp))
            if (recordedAt != null) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(recordedAt))
                Text(
                    "Recorded $date (v$version)",
                    color = OnSurfaceMuteDark,
                    fontSize = 13.sp
                )
            } else {
                Text(
                    "Not recorded",
                    color = Red,
                    fontSize = 13.sp
                )
            }
        }
        Button(
            onClick = onReRecord,
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = Color.Black
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = if (recordedAt != null) "RE-RECORD" else "SETUP",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, deepLink: (() -> Unit)? = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!ok && deepLink != null)
                    Modifier.clickable { deepLink() }
                else
                    Modifier
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AdLabel(
            text = label,
            modifier = Modifier.weight(1f),
            color = OnSurfaceVariantDark
        )
        Icon(
            imageVector = if (ok) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (ok) GreenOk else Red
        )
    }
}

@Composable
private fun OemSettingRow(setting: com.autodial.oem.OemSetting, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = setting.deepLinkIntent != null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            AdLabel(text = setting.displayName, color = OnSurfaceVariantDark)
            Spacer(Modifier.height(4.dp))
            Text(
                text = setting.description,
                color = OnSurfaceVariantDark,
                fontSize = 13.sp
            )
        }
        if (setting.deepLinkIntent != null) {
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariantDark,
                    contentColor = OnSurfaceVariantDark
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = "OPEN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
