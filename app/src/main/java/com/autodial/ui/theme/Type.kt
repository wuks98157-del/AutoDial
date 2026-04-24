package com.autodial.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// FontFamily.Monospace is the Android system monospaced default. The
// mockup specifies "Roboto Mono" — once a Roboto Mono font resource
// is added to res/font/, swap this to FontFamily(Font(R.font.roboto_mono_X)).
// Keeping as Monospace is a visually close fallback.
val MonoFamily = FontFamily.Monospace

val Typography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.7).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = MonoFamily, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
)
