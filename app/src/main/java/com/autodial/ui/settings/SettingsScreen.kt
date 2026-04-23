package com.autodial.ui.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.theme.GreenOk
import com.autodial.ui.theme.Red
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel(),
    onNavigateToWizard: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val settings = state.settings ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader("Defaults")
            NumberSetting("Default hang-up (s)", settings.defaultHangupSeconds, 1, 600,
                onSet = { vm.setDefaultHangup(it) })
            NumberSetting("Default cycles", settings.defaultCycles, 1, 9999,
                onSet = { vm.setDefaultCycles(it) })

            SectionHeader("Advanced")
            NumberSetting("Spam mode safety cap", settings.spamModeSafetyCap, 1, 99999,
                onSet = { vm.setSpamCap(it) })
            NumberSetting("Inter-digit delay (ms)", settings.interDigitDelayMs, 100, 2000,
                onSet = { vm.setInterDigitDelay(it) })

            SectionHeader("Recipes")
            RecipeRow("BizPhone", state.bizPhoneRecordedAt, state.bizPhoneVersion,
                onReRecord = { onNavigateToWizard("com.b3networks.bizphone") })
            RecipeRow("Mobile VOIP", state.mobileVoipRecordedAt, state.mobileVoipVersion,
                onReRecord = { onNavigateToWizard("finarea.MobileVoip") })

            SectionHeader("Device")
            PermRow("Accessibility Service", state.accessibilityOk) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Overlay Permission", state.overlayOk) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Notifications", state.notificationsOk, deepLink = null)
            PermRow("Battery Optimization Exempt", state.batteryOk) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            if (vm.oemHelper.oem != com.autodial.oem.OemCompatibilityHelper.Oem.GENERIC) {
                SectionHeader("OEM Settings (${vm.oemHelper.oem.name})")
                vm.oemHelper.getRequiredSettings().forEach { setting ->
                    OemSettingRow(setting, onClick = {
                        setting.deepLinkIntent?.let { context.startActivity(it) }
                    })
                }
            }

            SectionHeader("About")
            Text(
                "AutoDial v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}",
                modifier = Modifier.clickable { vm.tapVersion() },
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.devMenuUnlocked) {
                Text("Dev menu unlocked", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { onNavigateToWizard("com.b3networks.bizphone") }) {
                    Text("Force re-record BizPhone")
                }
                OutlinedButton(onClick = { onNavigateToWizard("finarea.MobileVoip") }) {
                    Text("Force re-record Mobile VOIP")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    HorizontalDivider()
}

@Composable
private fun NumberSetting(label: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (value > min) onSet(value - 1) }) { Text("−") }
            Text("$value", modifier = Modifier.width(48.dp))
            IconButton(onClick = { if (value < max) onSet(value + 1) }) { Text("+") }
        }
    }
}

@Composable
private fun RecipeRow(name: String, recordedAt: Long?, version: String?, onReRecord: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(name)
            if (recordedAt != null) {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(recordedAt))
                Text("Recorded $date (v$version)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Not recorded", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(onClick = onReRecord) {
            Text(if (recordedAt != null) "Re-record" else "Setup")
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, deepLink: (() -> Unit)? = {}) {
    Row(Modifier
        .fillMaxWidth()
        .then(if (!ok && deepLink != null) Modifier.clickable { deepLink() } else Modifier)
        .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Icon(
            if (ok) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (ok) GreenOk else Red
        )
    }
}

@Composable
private fun OemSettingRow(setting: com.autodial.oem.OemSetting, onClick: () -> Unit) {
    Column(Modifier
        .fillMaxWidth()
        .clickable(enabled = setting.deepLinkIntent != null) { onClick() }
        .padding(vertical = 8.dp)
    ) {
        Text(setting.displayName)
        Text(setting.description, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
