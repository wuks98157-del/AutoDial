package com.autodial.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.ui.theme.GreenOk
import com.autodial.ui.theme.OnSurfaceVariantDark
import com.autodial.ui.theme.Red
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: HistoryViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit
) {
    val runs by vm.runs.collectAsState()
    val events by vm.selectedRunEvents.collectAsState()
    var selectedRun by remember { mutableStateOf<RunRecord?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.Delete, "Clear history")
                    }
                }
            )
        }
    ) { padding ->
        if (runs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No runs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(runs) { run ->
                    RunRow(run, onClick = {
                        selectedRun = run
                        vm.loadStepEvents(run.id)
                    })
                    HorizontalDivider()
                }
            }
        }
    }

    if (selectedRun != null) {
        HistoryDetailSheet(events = events, onDismiss = { selectedRun = null })
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history") },
            text = { Text("Remove all run history?") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showClearDialog = false }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.clearOlderThan30Days(); showClearDialog = false
                }) { Text("Older than 30 days") }
            }
        )
    }
}

@Composable
private fun RunRow(run: RunRecord, onClick: () -> Unit) {
    val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(run.startedAt))
    val cycleText = if (run.plannedCycles == 0) "${run.completedCycles} / ∞"
                   else "${run.completedCycles} / ${run.plannedCycles}"
    val target = if (run.targetPackage == "com.b3networks.bizphone") "BizPhone" else "Mobile VOIP"
    val (statusLabel, statusColor) = when (run.status) {
        RunStatus.DONE -> "done" to GreenOk
        RunStatus.STOPPED -> "stopped" to OnSurfaceVariantDark
        RunStatus.FAILED -> "failed" to Red
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("${run.number}  ·  $cycleText  ·  $target",
                style = MaterialTheme.typography.bodyMedium)
            Text(date, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Badge(containerColor = statusColor) { Text(statusLabel) }
    }
}
