package com.autodial.ui.theme

import androidx.compose.ui.graphics.Color

// Palette matches docs: UI Design/AutoDial.html (the "AD" object).
// Keep hex values in sync with the mockup — if the mockup changes,
// update here too.
val Orange = Color(0xFFFF6B00)
val OrangeDeep = Color(0xFFE85D00)
val Red = Color(0xFFE53935)
val RedDeep = Color(0xFFC62828)
val GreenOk = Color(0xFF34C759)
val YellowWarn = Color(0xFFFFB300)  // retained; used by stale-recipe banner
val BackgroundDark = Color(0xFF0D0D0D)
val SurfaceDark = Color(0xFF1A1A1A)
val SurfaceVariantDark = Color(0xFF242424)  // "elevated" in mockup
val BorderDark = Color(0xFF2E2E2E)
val OnSurfaceDark = Color(0xFFFFFFFF)
val OnSurfaceVariantDark = Color(0xFFA0A0A0)
val OnSurfaceMuteDark = Color(0xFF6B6B6B)

// Preserved for compatibility; Theme.kt secondary field uses this
val OrangeLight = Color(0xFFFF9E40)
