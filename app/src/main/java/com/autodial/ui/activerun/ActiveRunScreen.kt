package com.autodial.ui.activerun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.model.RunState
import com.autodial.ui.common.*
import com.autodial.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ActiveRunScreen(
    vm: ActiveRunViewModel = hiltViewModel(),
    onRunEnd: () -> Unit,
) {
    val state by vm.runState.collectAsState()
    LaunchedEffect(state) {
        if (state is RunState.Completed || state is RunState.StoppedByUser ||
            state is RunState.Failed || state is RunState.Idle
        ) onRunEnd()
    }

    val display = when (val s = state) {
        is RunState.EnteringNumber -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Entering number", s.params.targetPackage)
        is RunState.PressingCall -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Pressing call", s.params.targetPackage)
        is RunState.InCall -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, s.hangupAt, "In call", s.params.targetPackage)
        is RunState.HangingUp -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Hanging up", s.params.targetPackage)
        else -> RunDisplay("", 0, 0, -1L, "", "")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            LiveBar()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AdLabel("Dialing via ${displayName(display.targetPackage)}")
                Spacer(Modifier.height(18.dp))
                Text(
                    display.number,
                    color = OnSurfaceDark,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFamily,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ATTEMPT ",
                        color = Orange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                    Text(
                        "${display.cycle}",
                        color = OnSurfaceDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                    Text(
                        " OF ",
                        color = Orange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                    Text(
                        if (display.plannedCycles == 0) "∞" else "${display.plannedCycles}",
                        color = OnSurfaceDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    )
                }
                Spacer(Modifier.height(22.dp))
                if (display.hangupAt > 0) HangupRing(display.hangupAt)
                else SubStateBadge(display.subState)
                Spacer(Modifier.height(22.dp))
                StatsStrip(
                    completed = (display.cycle - 1).coerceAtLeast(0),
                    remaining = if (display.plannedCycles == 0) -1 else (display.plannedCycles - display.cycle).coerceAtLeast(0),
                    hangupSec = ((display.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt(),
                )
                Spacer(Modifier.weight(1f))
                AdBigButton(
                    text = "Stop",
                    onClick = vm::stop,
                    containerColor = Red,
                    contentColor = Color.White,
                    shadowColor = RedDeep,
                    leading = { Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White) },
                )
            }
        }
    }
}

private data class RunDisplay(
    val number: String,
    val cycle: Int,
    val plannedCycles: Int,
    val hangupAt: Long,
    val subState: String,
    val targetPackage: String,
)

private fun displayName(pkg: String) = when (pkg) {
    "com.b3networks.bizphone" -> "BizPhone"
    "finarea.MobileVoip" -> "Mobile VOIP"
    else -> pkg
}

@Composable
private fun LiveBar() {
    val runStart = remember { System.currentTimeMillis() }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = (System.currentTimeMillis() - runStart) / 1000L
            delay(500L)
        }
    }
    val mm = (elapsed / 60).toString().padStart(2, '0')
    val ss = (elapsed % 60).toString().padStart(2, '0')
    Row(
        Modifier
            .fillMaxWidth()
            .background(Red)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdStatusDot(Color.White, size = 10.dp)
            Text(
                "LIVE — RUN IN PROGRESS",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.6.sp,
            )
        }
        Text(
            "$mm:$ss",
            color = Color.White,
            fontFamily = MonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HangupRing(hangupAt: Long) {
    val total = remember(hangupAt) { (hangupAt - System.currentTimeMillis()).coerceAtLeast(1L) }
    var remaining by remember(hangupAt) { mutableStateOf(1f) }
    var secsLeft by remember(hangupAt) { mutableStateOf(0L) }
    LaunchedEffect(hangupAt) {
        while (true) {
            val now = System.currentTimeMillis()
            remaining = ((hangupAt - now).toFloat() / total).coerceIn(0f, 1f)
            secsLeft = ((hangupAt - now) / 1000L).coerceAtLeast(0L)
            delay(100L)
        }
    }
    Box(Modifier.size(256.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val stroke = Stroke(strokeWidth)
            drawArc(
                color = SurfaceVariantDark,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(stroke.width / 2, stroke.width / 2),
                size = Size(size.width - stroke.width, size.height - stroke.width),
                style = stroke,
            )
            drawArc(
                color = Orange,
                startAngle = -90f,
                sweepAngle = remaining * 360f,
                useCenter = false,
                topLeft = Offset(stroke.width / 2, stroke.width / 2),
                size = Size(size.width - stroke.width, size.height - stroke.width),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$secsLeft",
                color = OnSurfaceDark,
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.height(8.dp))
            AdLabel("Sec until hang-up")
        }
    }
}

@Composable
private fun SubStateBadge(text: String) {
    Box(
        Modifier
            .background(SurfaceDark, RoundedCornerShape(999.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(999.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text.uppercase(),
            color = Orange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun StatsStrip(completed: Int, remaining: Int, hangupSec: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(4.dp)
            .height(IntrinsicSize.Min),
    ) {
        StatCell("Completed", "$completed", modifier = Modifier.weight(1f))
        StatDivider()
        StatCell("Remaining", if (remaining < 0) "∞" else "$remaining", modifier = Modifier.weight(1f))
        StatDivider()
        StatCell("Hang-up", "${hangupSec}s", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = OnSurfaceDark,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFamily,
        )
        Spacer(Modifier.height(2.dp))
        AdLabel(label)
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(BorderDark)
    )
}
