package com.autodial.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.data.db.entity.RunRecord
import com.autodial.data.db.entity.RunStatus
import com.autodial.ui.common.*
import com.autodial.ui.theme.*

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun HistoryScreen(
    vm: HistoryViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit
) {
    val runs by vm.runs.collectAsState()
    val events by vm.selectedRunEvents.collectAsState()
    var selectedRun by remember { mutableStateOf<RunRecord?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    Surface(color = BackgroundDark, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ──────────────────────────────────────────────────────
            AdHeader(
                title = {
                    Text(
                        "HISTORY",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.4.sp,
                        color = OnSurfaceDark,
                    )
                },
                left = {
                    AdIconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                right = {
                    AdIconButton(onClick = { showClearDialog = true }, color = Orange) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear history")
                    }
                },
            )

            // ── Summary strip ────────────────────────────────────────────────
            val runsCount = runs.size
            val callsCount = runs.sumOf { it.completedCycles }
            val successText = if (runs.isEmpty()) "—" else {
                val doneCount = runs.count { it.status == RunStatus.DONE }
                "${(doneCount.toFloat() / runsCount * 100).toInt()}%"
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BackgroundDark)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StatCell(value = runsCount.toString(), label = "Runs", modifier = Modifier.weight(1f))
                StatCell(value = callsCount.toString(), label = "Calls", modifier = Modifier.weight(1f))
                StatCell(value = successText, label = "Success", modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = BorderDark)

            // ── Body ─────────────────────────────────────────────────────────
            if (runs.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No runs yet",
                        color = OnSurfaceVariantDark,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            } else {
                // Build a flat list of sealed items so LazyColumn can key properly.
                val listItems = remember(runs) { buildListItems(runs) }

                LazyColumn(Modifier.fillMaxSize()) {
                    items(listItems, key = { it.key }) { item ->
                        when (item) {
                            is HistoryListItem.Header -> {
                                Box(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)) {
                                    AdLabel(item.label)
                                }
                            }
                            is HistoryListItem.Row -> {
                                RunRow(
                                    run = item.run,
                                    altBg = item.altBg,
                                    onClick = {
                                        selectedRun = item.run
                                        vm.loadStepEvents(item.run.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Detail sheet ─────────────────────────────────────────────────────────
    if (selectedRun != null) {
        HistoryDetailSheet(events = events, onDismiss = { selectedRun = null })
    }

    // ── Clear dialog ─────────────────────────────────────────────────────────
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history") },
            text = { Text("Remove all run history?") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showClearDialog = false }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearOlderThan30Days(); showClearDialog = false }) {
                    Text("Older than 30 days")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Sealed type for LazyColumn items — either a date-group header or a run row. */
private sealed class HistoryListItem {
    abstract val key: String

    data class Header(val label: String) : HistoryListItem() {
        override val key: String get() = "header:$label"
    }

    data class Row(val run: RunRecord, val altBg: Boolean) : HistoryListItem() {
        override val key: String get() = "row:${run.id}"
    }
}

/**
 * Fold [runs] (already sorted newest-first by the VM) into a flat list of
 * [HistoryListItem]s, inserting a [HistoryListItem.Header] whenever the day-
 * group key changes.  [altBg] alternates per row within a group.
 */
private fun buildListItems(runs: List<RunRecord>): List<HistoryListItem> {
    val result = mutableListOf<HistoryListItem>()
    var lastKey: String? = null
    var groupIndex = 0

    for (run in runs) {
        val key = groupKey(run.startedAt)
        if (key != lastKey) {
            result += HistoryListItem.Header(key)
            lastKey = key
            groupIndex = 0
        }
        result += HistoryListItem.Row(run = run, altBg = groupIndex % 2 == 0)
        groupIndex++
    }
    return result
}

/**
 * Returns "Today", "Yesterday", or a formatted date string for the given
 * [timestampMs].
 */
private fun groupKey(timestampMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val oneDay = 86_400_000L
    return when {
        timestampMs >= startOfToday -> "Today"
        timestampMs >= startOfToday - oneDay -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(timestampMs))
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            fontFamily = MonoFamily,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDark,
        )
        Spacer(Modifier.height(4.dp))
        AdLabel(label)
    }
}

@Composable
private fun RunRow(run: RunRecord, altBg: Boolean, onClick: () -> Unit) {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(run.startedAt))
    val target = if (run.targetPackage == "com.b3networks.bizphone") "BizPhone" else "Mobile VOIP"
    val statusColor = when (run.status) {
        RunStatus.DONE -> GreenOk
        RunStatus.STOPPED -> Orange
        RunStatus.FAILED -> Red
    }
    val cyclesText = if (run.plannedCycles == 0) "${run.completedCycles}/∞"
                     else "${run.completedCycles}/${run.plannedCycles}"
    val cyclesColor = when (run.status) {
        RunStatus.FAILED -> Red
        RunStatus.STOPPED -> Orange
        else -> OnSurfaceDark
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (altBg) SurfaceDark else BackgroundDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdStatusDot(statusColor)

        Column(Modifier.weight(1f)) {
            Text(
                run.number,
                color = OnSurfaceDark,
                fontFamily = MonoFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdLabel(time)
                AdLabel("·")
                AdLabel(target)
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                cyclesText,
                color = cyclesColor,
                fontFamily = MonoFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            AdLabel("Cycles")
        }
    }
    HorizontalDivider(color = BorderDark, thickness = 1.dp)
}
