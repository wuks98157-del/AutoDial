package com.autodial.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autodial.data.db.entity.RunStepEvent
import com.autodial.ui.theme.GreenOk
import com.autodial.ui.theme.Red
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailSheet(
    events: List<RunStepEvent>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Step Timeline", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(events) { event ->
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(event.at))
                val isOk = event.outcome.startsWith("ok:")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("[${event.cycleIndex}] ${event.stepId}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(event.outcome, color = if (isOk) GreenOk else Red,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Text(time, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
