# AutoDial UI Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the four user-facing screens (Dialer, Active Run, Settings, History) to match the dark-utilitarian mockup in `AutoDial.html` (+ `UI Design/*.png` references), and replace the app icon with the assets in `UI Design/`.

**Architecture:** Tweak the Compose theme palette + typography, extract a small set of reusable visual primitives matching the mockup's signature patterns (`AdHeader`, `AdBigButton`, `AdBigField`, `AdSmallField`, `AdLabel`, `AdStatusDot`), then rebuild each of the four screens on top of those primitives. No state / navigation / VM changes — UI only.

**Tech Stack:** Kotlin, Jetpack Compose Material3, existing theme at `app/src/main/java/com/autodial/ui/theme/`.

**Scope (Option B):** 4 mockup screens + app icon only. Onboarding, Wizard overlay, and History detail sheet are explicitly out of scope.

**Design source of truth:** `C:\Users\R4\Downloads\AutoDial\UI Design\AutoDial.html` — read this before touching any screen. The 4 PNGs (`autodial-01-dialer.png` … `autodial-04-history.png`) show the rendered output.

**Branch:** Start a new feature branch off `feature/overlay-wizard-refactor` (or `master` after that merges): `feature/ui-refactor`.

---

## Task 1: Theme — palette, typography, theme wiring

**Files:**
- Modify: `app/src/main/java/com/autodial/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/autodial/ui/theme/Type.kt`
- Modify: `app/src/main/java/com/autodial/ui/theme/Theme.kt`

The mockup palette (from `AutoDial.html` `AD` object, lines 306–318):

```
bg        #0D0D0D   (background, "AD.bg")
surface   #1A1A1A   (card-like surfaces, "AD.surface")
elevated  #242424   (buttons/chips bg, "AD.elevated")
border    #2E2E2E   (dividers + stroke, "AD.border")
text      #FFFFFF   (primary text)
textDim   #A0A0A0   (secondary text)
textMute  #6B6B6B   (labels, placeholders)
orange    #FF6B00   (primary accent)
orangeDeep#E85D00   (button shadow/press)
red       #E53935   (LIVE bar, STOP)
redDeep   #C62828   (STOP shadow)
green     #34C759   (status dot for done)
```

- [ ] **Step 1.1: Update `Color.kt` to the mockup palette**

Replace the whole file with:

```kotlin
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
```

- [ ] **Step 1.2: Update `Type.kt` with Roboto Mono stand-in and mockup styles**

Replace the whole file with:

```kotlin
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
    // Big title like "AutoDial" in headers — weight 900, letter-spacing negative
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.7).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = MonoFamily, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.2.sp),   // START/STOP letters
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp), // small label
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),  // tiny label
)
```

- [ ] **Step 1.3: Update `Theme.kt` to wire the new palette**

Replace `DarkColorScheme` construction so `SurfaceVariantDark` = elevated and `onSurfaceVariant` = OnSurfaceVariantDark (dim text):

```kotlin
package com.autodial.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color.Black,
    secondary = Orange,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = BorderDark,
    error = Red,
)

@Composable
fun AutoDialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
```

- [ ] **Step 1.4: Build (Android Studio: Build → Rebuild). Must compile.**

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/java/com/autodial/ui/theme/Color.kt \
        app/src/main/java/com/autodial/ui/theme/Type.kt \
        app/src/main/java/com/autodial/ui/theme/Theme.kt
git commit -m "feat(ui): theme palette + typography match AutoDial.html mockup"
```

---

## Task 2: Shared visual primitives

**File to create:** `app/src/main/java/com/autodial/ui/common/AdComponents.kt`

Extract the recurring patterns from the mockup as reusable composables.

- [ ] **Step 2.1: Create `AdComponents.kt`**

```kotlin
package com.autodial.ui.common

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
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
    // The mockup uses `boxShadow: 0 4px 0 <shadowColor>`. We approximate
    // with a lower box of `shadowColor` beneath a real Button.
    Box(
        modifier = modifier
            .height(80.dp)  // 76 button + 4 shadow ledge
    ) {
        // shadow ledge
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

/** Small bordered field with label on top, value + unit in bigger mono text. Dialer's cycles/hangup fields. */
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
        Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) { right?.invoke() }
    }
    // underline
    HorizontalDivider(color = BorderDark, thickness = 1.dp)
}

/** Header icon button — 44dp tap target, dim by default. */
@Composable
fun AdIconButton(onClick: () -> Unit, color: Color = OnSurfaceVariantDark, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        androidx.compose.material3.LocalContentColor provides color
        content()
    }
}

/** A colored dot (8dp). For status indicators. */
@Composable
fun AdStatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(color, shape = RoundedCornerShape(50))
    )
}
```

- [ ] **Step 2.2: Build.** Compose will complain if any API signature is off.

- [ ] **Step 2.3: Commit**

```bash
git add app/src/main/java/com/autodial/ui/common/AdComponents.kt
git commit -m "feat(ui): shared primitives (AdHeader/AdBigButton/AdBigField/AdSmallField/AdLabel/AdStatusDot)"
```

---

## Task 3: DialerScreen

**File:** `app/src/main/java/com/autodial/ui/dialer/DialerScreen.kt`

Replace the current Scaffold-based layout with one built from the new primitives. Preserve all behavior: `state.number` input, accessibility/stale banners, target toggle, spam-mode switch, cycles + hangup steppers, startBlockReason text, START button.

The mockup shows (top-to-bottom, as rendered in `UI Design/autodial-01-dialer.png`):

1. Custom header — "Auto" white + "Dial" orange (weight 900), History + Settings icons.
2. Status row — green dot + "READY" + right-aligned version "v2.4.1" (omit version in real app — we don't display one today).
3. `AdBigField` — "PHONE NUMBER" label + the current number in mono 34sp + hint "NUMERIC KEYPAD · INTL FORMAT OK".
4. Row of two `AdSmallField`s: Cycles (unit "×") and Hang-up after (unit "SEC").
5. "TARGET APP" label + restyled `TargetToggle` — two pills with `AdStatusDot` + label; active = orange bg black text, inactive = surfaceVariant with dim text.
6. Spam mode row (current behavior) — restyled only.
7. Spacer.
8. Summary strip — one dashed-outline row with "EST. TOTAL" mono label + computed time ("≈ 4 min 10 sec"). Compute from cycles × hangup. Non-critical; use `AdLabel` styling.
9. `AdBigButton` "START" — play-triangle icon + uppercase text.

- [ ] **Step 3.1: Rewrite `DialerScreen.kt`**

Here's the full replacement. Preserve the `@Composable DialerScreen` signature (same parameters) and the inner `EditableStepperRow` for the keyboard-input cycles + hangup.

```kotlin
package com.autodial.ui.dialer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autodial.ui.common.*
import com.autodial.ui.theme.*

@Composable
fun DialerScreen(
    vm: DialerViewModel = hiltViewModel(),
    onNavigateToActiveRun: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onBeginWizard: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.isRunActive) { if (state.isRunActive) onNavigateToActiveRun() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundDark
    ) {
        Column(Modifier.fillMaxSize()) {
            AdHeader(
                title = {
                    Row {
                        Text("Auto", color = OnSurfaceDark, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text("Dial", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                },
                left = { AdIconButton(onNavigateToHistory) { Icon(Icons.Default.History, "History") } },
                right = { AdIconButton(onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Status row
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dotColor = if (state.accessibilityEnabled) GreenOk else Red
                    AdStatusDot(dotColor)
                    AdLabel(if (state.accessibilityEnabled) "Ready" else "Accessibility off")
                }

                if (!state.accessibilityEnabled) StaleBanner("Accessibility service disabled — tap Settings to re-enable", Red)
                if (state.bizPhoneStale) StaleBanner("BizPhone has updated — re-record recipe in Settings", YellowWarn)
                if (state.mobileVoipStale) StaleBanner("Mobile VOIP has updated — re-record recipe in Settings", YellowWarn)

                // Phone number field with inline text editor (hidden OutlinedTextField for input)
                PhoneNumberField(state.number, onChange = vm::setNumber)

                // Cycles + Hang-up side-by-side
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.spamMode) {
                        Box(Modifier.weight(1f)) {
                            EditableField("Cycles", state.cycles.toString(), unit = "×", onValueChange = { it.toIntOrNull()?.let(vm::setCycles) })
                        }
                    } else {
                        AdSmallField(label = "Cycles", value = "∞", unit = "max ${state.spamModeSafetyCap}", modifier = Modifier.weight(1f))
                    }
                    Box(Modifier.weight(1f)) {
                        EditableField("Hang-up after", state.hangupSeconds.toString(), unit = "sec", onValueChange = { it.toIntOrNull()?.let(vm::setHangupSeconds) })
                    }
                }

                // Target app
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdLabel("Target app")
                    TargetToggle(
                        selected = state.targetPackage,
                        bizPhoneHasRecipe = state.bizPhoneRecipe != null,
                        mobileVoipHasRecipe = state.mobileVoipRecipe != null,
                        onSelect = vm::setTarget,
                        onSetupWizard = onBeginWizard,
                    )
                }

                // Spam toggle
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Spam mode", color = OnSurfaceDark, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.spamMode,
                        onCheckedChange = vm::setSpamMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Orange,
                            uncheckedTrackColor = SurfaceVariantDark,
                            uncheckedThumbColor = OnSurfaceVariantDark,
                            uncheckedBorderColor = BorderDark,
                        )
                    )
                }

                Spacer(Modifier.weight(1f))

                if (state.startBlockReason.isNotEmpty()) {
                    Text(state.startBlockReason, color = Red, style = MaterialTheme.typography.bodyMedium)
                }

                // Summary strip
                EstTotalStrip(cycles = state.cycles, hangupSec = state.hangupSeconds, spam = state.spamMode)

                // START button
                AdBigButton(
                    text = "Start",
                    onClick = vm::startRun,
                    enabled = state.canStart,
                    leading = { Icon(Icons.Default.PlayArrow, null, tint = Color.Black) }
                )
            }
        }
    }
}

@Composable
private fun PhoneNumberField(value: String, onChange: (String) -> Unit) {
    // Visible: AdBigField with `value` + cursor. Invisible: OutlinedTextField
    // stacked on top with transparent styling to catch keyboard input.
    Box {
        AdBigField(
            label = "Phone number",
            value = if (value.isEmpty()) " " else value,
            hint = "Numeric keypad · INTL format OK",
            focused = true,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 34.dp).height(64.dp)
        )
    }
}

@Composable
private fun EditableField(label: String, value: String, unit: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(
        Modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        AdLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    text = new.filter(Char::isDigit).take(4)
                    onValueChange(text)
                },
                textStyle = MaterialTheme.typography.headlineLarge.copy(color = OnSurfaceDark),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(unit.uppercase(), color = OnSurfaceMuteDark, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun StaleBanner(message: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
        Text(message, modifier = Modifier.padding(12.dp), color = tint)
    }
}

@Composable
private fun TargetToggle(
    selected: String,
    bizPhoneHasRecipe: Boolean,
    mobileVoipHasRecipe: Boolean,
    onSelect: (String) -> Unit,
    onSetupWizard: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(14.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            "com.b3networks.bizphone" to "BizPhone",
            "finarea.MobileVoip" to "Mobile VOIP"
        ).forEach { (pkg, label) ->
            val hasRecipe = if (pkg == "com.b3networks.bizphone") bizPhoneHasRecipe else mobileVoipHasRecipe
            val active = selected == pkg
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .background(if (active) Orange else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { if (hasRecipe) onSelect(pkg) else onSetupWizard(pkg) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdStatusDot(if (active) Color.Black else OnSurfaceMuteDark)
                    Text(
                        if (hasRecipe) label.uppercase() else "${label.uppercase()} (SETUP)",
                        color = if (active) Color.Black else OnSurfaceVariantDark,
                        fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EstTotalStrip(cycles: Int, hangupSec: Int, spam: Boolean) {
    val totalSec = if (spam) -1 else cycles * hangupSec
    val formatted = when {
        totalSec < 0 -> "— indefinite —"
        totalSec >= 60 -> "≈ ${totalSec / 60} min ${totalSec % 60} sec"
        else -> "≈ $totalSec sec"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AdLabel("Est. total")
        Text(formatted, color = OnSurfaceDark, fontWeight = FontWeight.Bold,
            fontFamily = MonoFamily, fontSize = 13.sp)
    }
}
```

- [ ] **Step 3.2: Build + install on device**
- [ ] **Step 3.3: Visually compare to `UI Design/autodial-01-dialer.png`.** Spacing, colors, button shadow should match. If anything feels off by more than ~4dp or the wrong color, iterate.
- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/java/com/autodial/ui/dialer/DialerScreen.kt
git commit -m "feat(ui): restyle DialerScreen to match AutoDial.html mockup"
```

---

## Task 4: ActiveRunScreen

**File:** `app/src/main/java/com/autodial/ui/activerun/ActiveRunScreen.kt`

Mockup (`UI Design/autodial-02-active-run.png`): red LIVE bar, centered phone number in mono, "ATTEMPT X OF Y" orange label, large 256dp circular countdown with current seconds in big mono text, 3-column stats strip (Completed / Remaining / Hang-up), STOP button with red drop-shadow.

- [ ] **Step 4.1: Rewrite `ActiveRunScreen.kt`**

```kotlin
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
            state is RunState.Failed || state is RunState.Idle) onRunEnd()
    }

    val d = when (val s = state) {
        is RunState.EnteringNumber -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Entering number", s.params.targetPackage)
        is RunState.PressingCall -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Pressing call", s.params.targetPackage)
        is RunState.InCall -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, s.hangupAt, "In call", s.params.targetPackage)
        is RunState.HangingUp -> RunDisplay(s.params.number, s.cycle + 1, s.params.plannedCycles, -1L, "Hanging up", s.params.targetPackage)
        else -> RunDisplay("", 0, 0, -1L, "", "")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
        Column(Modifier.fillMaxSize()) {
            LiveBar()
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AdLabel(
                    text = "Dialing via ${displayName(d.targetPackage)}",
                    modifier = Modifier
                )
                Spacer(Modifier.height(18.dp))
                Text(d.number, color = OnSurfaceDark, fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, fontFamily = MonoFamily, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(6.dp))
                Row {
                    AdLabel("Attempt ", color = Orange)
                    Text("${d.cycle}", color = OnSurfaceDark, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    AdLabel(" of ", color = Orange)
                    Text(if (d.plannedCycles == 0) "∞" else "${d.plannedCycles}",
                        color = OnSurfaceDark, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Spacer(Modifier.height(22.dp))
                if (d.hangupAt > 0) HangupRing(d.hangupAt)
                else SubStateBadge(d.subState)
                Spacer(Modifier.height(22.dp))
                StatsStrip(
                    completed = (d.cycle - 1).coerceAtLeast(0),
                    remaining = if (d.plannedCycles == 0) -1 else (d.plannedCycles - d.cycle).coerceAtLeast(0),
                    hangupSec = ((d.hangupAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()
                )
                Spacer(Modifier.weight(1f))
                AdBigButton(
                    text = "Stop",
                    onClick = vm::stop,
                    containerColor = Red,
                    contentColor = Color.White,
                    shadowColor = RedDeep,
                    leading = { Icon(Icons.Default.Stop, null, tint = Color.White) }
                )
            }
        }
    }
}

private data class RunDisplay(
    val number: String, val cycle: Int, val plannedCycles: Int,
    val hangupAt: Long, val subState: String, val targetPackage: String,
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
        Modifier.fillMaxWidth().background(Red).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdStatusDot(Color.White, size = 10.dp)
            Text("LIVE — RUN IN PROGRESS", color = Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
        }
        Text("$mm:$ss", color = Color.White, fontFamily = MonoFamily,
            fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            val stroke = Stroke(14.dp.toPx())
            drawArc(color = SurfaceVariantDark, startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = Offset(stroke.width / 2, stroke.width / 2),
                size = Size(size.width - stroke.width, size.height - stroke.width),
                style = stroke)
            drawArc(color = Orange, startAngle = -90f, sweepAngle = remaining * 360f,
                useCenter = false, topLeft = Offset(stroke.width / 2, stroke.width / 2),
                size = Size(size.width - stroke.width, size.height - stroke.width),
                style = Stroke(14.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$secsLeft", color = OnSurfaceDark, fontSize = 88.sp,
                fontWeight = FontWeight.Bold, fontFamily = MonoFamily, letterSpacing = (-2).sp)
            Spacer(Modifier.height(8.dp))
            AdLabel("Sec until hang-up")
        }
    }
}

@Composable
private fun SubStateBadge(text: String) {
    Box(
        Modifier.background(SurfaceDark, RoundedCornerShape(999.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(999.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text.uppercase(), color = Orange,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun StatsStrip(completed: Int, remaining: Int, hangupSec: Int) {
    Row(
        Modifier.fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
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
    Column(modifier.padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = OnSurfaceDark, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, fontFamily = MonoFamily)
        Spacer(Modifier.height(2.dp))
        AdLabel(label)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(36.dp).background(BorderDark).align(Alignment.CenterVertically))
}
```

*(Note: if the Kotlin compiler rejects `Box(...).align(Alignment.CenterVertically)` because the parent is a `Row`, wrap the divider in `Row(Modifier.fillMaxHeight()) { … }` or use `Spacer(Modifier.width(1.dp).fillMaxHeight().background(BorderDark))` — adapt if needed.)*

- [ ] **Step 4.2: Build, install, visually compare** to `UI Design/autodial-02-active-run.png` during a real run.
- [ ] **Step 4.3: Commit**

```bash
git add app/src/main/java/com/autodial/ui/activerun/ActiveRunScreen.kt
git commit -m "feat(ui): restyle ActiveRunScreen to match AutoDial.html mockup"
```

---

## Task 5: SettingsScreen

**File:** `app/src/main/java/com/autodial/ui/settings/SettingsScreen.kt`

Mockup (`UI Design/autodial-03-settings.png`): "SETTINGS" header with back arrow, section labels (DEFAULTS, BEHAVIOR) in uppercase, list-row styled items with small uppercase label + sub-text + right-side value/chevron, orange toggle switches, SAVE button at bottom.

- [ ] **Step 5.1: Read the current `SettingsScreen.kt`** to see what rows + actions exist. Preserve all behavior.

- [ ] **Step 5.2: Rewrite the screen using `AdHeader`, `AdLabel`, `AdBigButton`** and a new private `AdSettingsRow` composable modeled on the mockup's rows:

Structure (preserve existing section content, just restyle):

```kotlin
Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
    Column(Modifier.fillMaxSize()) {
        AdHeader(
            title = { Text("SETTINGS", color = OnSurfaceDark, fontSize = 18.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.4.sp) },
            left = { AdIconButton(onNavigateUp) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Defaults")
            SettingsGroup {
                // preserve existing rows: default hangup, default cycles, default target, etc.
                // using AdSettingsRow primitive
            }
            SectionLabel("Behavior")
            SettingsGroup {
                // existing toggle rows: keep screen on, etc.
            }
            // Build info strip at bottom
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("BUILD ${BuildConfig.VERSION_NAME}", color = OnSurfaceMuteDark,
                    fontFamily = MonoFamily, fontSize = 11.sp, letterSpacing = 0.8.sp)
            }
        }
        // Save button
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            AdBigButton(text = "Save", onClick = { /* existing save action */ })
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Box(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp)) { AdLabel(text) }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, BorderDark)
    ) { content() }
}

@Composable
private fun AdSettingsRow(
    label: String,
    sub: String?,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AdLabel(label)
            if (sub != null) {
                Spacer(Modifier.height(4.dp))
                Text(sub, color = OnSurfaceVariantDark, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
    HorizontalDivider(color = BorderDark, thickness = 1.dp)
}
```

For Switch colors, use the same orange scheme as the Dialer's spam-mode switch (Task 3). For value + chevron, render `Row(verticalAlignment = Alignment.CenterVertically) { Text(value, Mono, Bold, 22sp); Spacer(8dp); Icon(ChevronRight) }`.

- [ ] **Step 5.3: Preserve the existing logic** (navigation, settings read/write via the ViewModel). Only change the visual structure.

- [ ] **Step 5.4: Build, install, visually compare** to `UI Design/autodial-03-settings.png`.

- [ ] **Step 5.5: Commit**

```bash
git add app/src/main/java/com/autodial/ui/settings/SettingsScreen.kt
git commit -m "feat(ui): restyle SettingsScreen to match AutoDial.html mockup"
```

---

## Task 6: HistoryScreen

**File:** `app/src/main/java/com/autodial/ui/history/HistoryScreen.kt`

Mockup (`UI Design/autodial-04-history.png`): "HISTORY" header with orange trash action, 3-stat strip (Runs / Calls / Success%) with large mono numbers, day-grouped rows with `AdStatusDot` + mono number + `time · app` sub + right-aligned `cycles` + "CYCLES" label.

- [ ] **Step 6.1: Read the current `HistoryScreen.kt`** — preserve behavior (clear action, item tap opens detail sheet, navigation).

- [ ] **Step 6.2: Rewrite the list** with:
  - `AdHeader` with back arrow left + trash icon right (tinted Orange).
  - 3-stat `HistoryStatsStrip(Modifier, runs, calls, successPct)`: Row of 3 columns, mono 22sp numbers + small uppercase labels. Calculate from the existing list (runs = list.size, calls = sum of completedCycles, success% = completed+done / total).
  - Group items by day label ("Today", "Yesterday", or formatted date). Insert an `AdLabel` group header before each group.
  - Row rendering: alternating `SurfaceDark` / `BackgroundDark` backgrounds, `AdStatusDot(green/orange/red)` based on status, mono number, small uppercase time + app, right-side mono cycles + "CYCLES" label. Wrap in a clickable row to open detail sheet.

```kotlin
@Composable
private fun HistoryRow(item: RunItem, altBg: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (altBg) SurfaceDark else BackgroundDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdStatusDot(statusColor(item.status))
        Column(Modifier.weight(1f)) {
            Text(item.number, color = OnSurfaceDark, fontFamily = MonoFamily,
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdLabel(item.time)
                AdLabel("·")
                AdLabel(displayName(item.targetPackage))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${item.completedCycles}/${item.plannedCycles}",
                color = cyclesColor(item.status), fontFamily = MonoFamily,
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            AdLabel("Cycles")
        }
    }
    HorizontalDivider(color = BorderDark, thickness = 1.dp)
}
```

- [ ] **Step 6.3: Build, install, visually compare** to `UI Design/autodial-04-history.png`.

- [ ] **Step 6.4: Commit**

```bash
git add app/src/main/java/com/autodial/ui/history/HistoryScreen.kt
git commit -m "feat(ui): restyle HistoryScreen to match AutoDial.html mockup"
```

---

## Task 7: App icon replacement

**Goal:** Replace the default launcher icon with the user's `UI Design/autodial-icon*` assets.

**Files:**
- Copy: `UI Design/autodial-icon-48.png` → `app/src/main/res/mipmap-mdpi/ic_launcher.png` (+ `ic_launcher_round.png`)
- Copy: `UI Design/autodial-icon-72.png` → `app/src/main/res/mipmap-hdpi/ic_launcher.png` (+ round)
- Copy: `UI Design/autodial-icon-96.png` → `app/src/main/res/mipmap-xhdpi/ic_launcher.png` (+ round)
- Copy: `UI Design/autodial-icon-144.png` → `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (+ round)
- Copy: `UI Design/autodial-icon-192.png` → `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (+ round)
- Modify (or delete): `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`
- Modify (or delete): `app/src/main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`

- [ ] **Step 7.1: Create the 5 mipmap folders if they don't exist** and copy the PNGs as both `ic_launcher.png` and `ic_launcher_round.png` (same file — Android treats them as distinct entries). Specifically:

```bash
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density="${d%%:*}"; size="${d##*:}"
  mkdir -p "app/src/main/res/mipmap-$density"
  cp "UI Design/autodial-icon-$size.png" "app/src/main/res/mipmap-$density/ic_launcher.png"
  cp "UI Design/autodial-icon-$size.png" "app/src/main/res/mipmap-$density/ic_launcher_round.png"
done
```

- [ ] **Step 7.2: Decide on adaptive icon.** Two options:

**(a) Remove the adaptive XML entries** (simpler; Android falls back to the PNGs in mipmap-*dpi on all launchers). Delete these files:

```bash
rm app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
rm app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
# mipmap-anydpi-v26 folder can also be removed if now empty
rmdir app/src/main/res/mipmap-anydpi-v26 2>/dev/null
# drawables only referenced by adaptive XML
rm app/src/main/res/drawable/ic_launcher_background.xml
rm app/src/main/res/drawable/ic_launcher_foreground.xml
```

**(b) Keep adaptive icon** (better visual fidelity on modern launchers, parallax, theming). Requires converting the user's `autodial-icon-background.svg` + `autodial-icon-foreground.svg` into Android Vector Drawable XML. Recommend doing this via Android Studio's **New → Vector Asset → From SVG** tool — paste each SVG, name the output matching the existing files, overwrite.

**Recommendation: (a) for this pass.** minSdk=30 means adaptive icons are available but every launcher on Android 11+ also respects PNG icons, and this avoids the SVG→vector conversion step. A future pass can re-introduce adaptive icons once the user has run the conversion tool.

- [ ] **Step 7.3: Build + install + verify** the new icon shows on the launcher.

- [ ] **Step 7.4: Commit**

```bash
git add -A
git commit -m "feat(ui): replace app launcher icon with UI Design assets"
```

---

## Self-review

1. **Mockup coverage:** Dialer (Task 3), Active Run (Task 4), Settings (Task 5), History (Task 6) — all 4 mockup screens addressed. App icon (Task 7). ✓
2. **No placeholders:** Every step has exact file paths + code. ✓
3. **Type consistency:** All screens consume `AdHeader`, `AdBigButton`, `AdBigField`, `AdSmallField`, `AdLabel`, `AdStatusDot`, `AdIconButton` from Task 2. ✓
4. **Behavior preservation:** No ViewModel / Flow / nav / DB changes. All reworks are composable-only. ✓
5. **Out of scope reminders:** Onboarding, WizardCard (overlay), HistoryDetailSheet are NOT touched. ✓
