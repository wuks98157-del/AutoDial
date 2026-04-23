package com.autodial.ui.activerun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.model.RunState
import com.autodial.ui.theme.Orange
import com.autodial.ui.theme.Red

@Composable
fun ActiveRunScreen(
    vm: ActiveRunViewModel = hiltViewModel(),
    onRunEnd: () -> Unit
) {
    val state by vm.runState.collectAsState()

    LaunchedEffect(state) {
        if (state is RunState.Completed || state is RunState.StoppedByUser ||
            state is RunState.Failed || state is RunState.Idle) {
            onRunEnd()
        }
    }

    val (number, cycleText, subState, hangupAt) = when (val s = state) {
        is RunState.TypingDigits -> RunDisplay(s.params.number, "Cycle ${s.cycle + 1} / ${s.params.plannedCycles.let { if (it == 0) "∞" else it.toString() }}", "Dialing", -1L)
        is RunState.PressingCall -> RunDisplay(s.params.number, "Cycle ${s.cycle + 1}", "Pressing call", -1L)
        is RunState.InCall -> RunDisplay(s.params.number, "Cycle ${s.cycle + 1}", "In call", s.hangupAt)
        is RunState.HangingUp -> RunDisplay(s.params.number, "Cycle ${s.cycle + 1}", "Hanging up", -1L)
        is RunState.ReturningToDialPad -> RunDisplay(s.params.number, "Cycle ${s.cycle + 1}", "Returning to dial pad", -1L)
        else -> RunDisplay("", "", "", -1L)
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(number, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(cycleText, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        if (hangupAt > 0) {
            HangupCountdownRing(hangupAt)
            Spacer(Modifier.height(16.dp))
        }

        Text(subState, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { vm.stop() },
            colors = ButtonDefaults.buttonColors(containerColor = Red),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("STOP", fontSize = 18.sp)
        }
    }
}

private data class RunDisplay(val number: String, val cycleText: String, val subState: String, val hangupAt: Long)

@Composable
private fun HangupCountdownRing(hangupAt: Long) {
    val total = remember { (hangupAt - System.currentTimeMillis()).coerceAtLeast(1L) }
    val remaining by produceState(1f) {
        while (true) {
            val now = System.currentTimeMillis()
            value = ((hangupAt - now).toFloat() / total).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(100)
        }
    }

    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(12.dp.toPx())
            val sweep = remaining * 360f
            drawArc(
                color = Orange,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(stroke.width / 2, stroke.width / 2),
                size = Size(size.width - stroke.width, size.height - stroke.width),
                style = stroke
            )
        }
        Text(
            text = "${((hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)}s",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
