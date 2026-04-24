package com.autodial.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodial.ui.theme.*

/** Uppercase, wide-letter-spaced label text. Matches mockup's section + field labels. */
@Composable
fun AdLabel(text: String, modifier: Modifier = Modifier, color: Color = OnSurfaceMuteDark) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
}

/** The chunky 76dp primary button with a visible drop-shadow ledge. */
@Composable
fun AdBigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Orange,
    contentColor: Color = Color.Black,
    shadowColor: Color = OrangeDeep,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        // shadow ledge underneath
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .offset(y = 4.dp)
                .background(shadowColor, RoundedCornerShape(14.dp))
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.4f),
                disabledContentColor = contentColor.copy(alpha = 0.6f),
            ),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
    }
}

/** Big bordered field containing label + large value + small hint below. Dialer's number field. */
@Composable
fun AdBigField(
    label: String,
    value: String,
    hint: String? = null,
    focused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(
                width = 2.dp,
                color = if (focused) Orange else BorderDark,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Column {
            AdLabel(label)
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                color = OnSurfaceDark,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
                letterSpacing = 0.6.sp,
            )
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hint.uppercase(),
                    color = OnSurfaceMuteDark,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/** Small bordered field: label + mono value + optional unit. Dialer's cycles/hangup fields. */
@Composable
fun AdSmallField(
    label: String,
    value: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        AdLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = OnSurfaceDark,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
            )
            if (unit != null) {
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
}

/** Top bar: centered title with optional left + right icon slots. */
@Composable
fun AdHeader(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    left: @Composable (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier
                .fillMaxWidth()
                .background(BackgroundDark)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.widthIn(min = 72.dp)) { left?.invoke() }
            title()
            Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) {
                right?.invoke()
            }
        }
        HorizontalDivider(color = BorderDark, thickness = 1.dp)
    }
}

/** Header icon button — 44dp tap target, configurable tint. */
@Composable
fun AdIconButton(
    onClick: () -> Unit,
    color: Color = OnSurfaceVariantDark,
    content: @Composable () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            content()
        }
    }
}

/** A colored dot (8dp default). For status indicators. */
@Composable
fun AdStatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(color, shape = RoundedCornerShape(50))
    )
}
