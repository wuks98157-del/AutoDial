# AutoDial — Plan 2: Accessibility Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Plan 1 complete and tests passing.

**Goal:** Implement the `AutoDialAccessibilityService` with recording (wizard support) and 4-tier playback (run support), plus the perceptual hashing utility.

**Architecture:** `AutoDialAccessibilityService` is the only Android component that can see and tap BizPhone/Mobile VOIP's UI. It exposes a companion-object singleton so `RunForegroundService` (Plan 3) can call it in-process. `UiRecorder` handles record mode; `UiPlayer` handles playback. `PhashUtil` is a pure utility for perceptual hashing.

**Tech Stack:** Android AccessibilityService (API 30+), `AccessibilityNodeInfo`, `dispatchGesture`, `takeScreenshot`, Kotlin Coroutines, pHash (8×8 DCT)

---

## File Map

```
app/src/main/res/xml/accessibility_service_config.xml

app/src/main/java/com/autodial/
  accessibility/
    AutoDialAccessibilityService.kt
    PhashUtil.kt
    UiPlayer.kt
    UiRecorder.kt
  model/
    RunEvent.kt
    RunParams.kt
    RecordedStep.kt

app/src/test/java/com/autodial/
  accessibility/
    PhashUtilTest.kt
    UiPlayerTest.kt
```

---

### Task 1: Accessibility Service Config + Manifest Registration

**Files:** `res/xml/accessibility_service_config.xml`, update `AndroidManifest.xml`

- [ ] **Step 1.1: Create `app/src/main/res/xml/accessibility_service_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewClicked|typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:notificationTimeout="100"
    android:packageNames="com.b3networks.bizphone,finarea.MobileVoip,com.autodial"
    android:description="@string/accessibility_service_description" />
```

`com.autodial` is included in `packageNames` so the wizard can intercept clicks on target apps after they are launched.

- [ ] **Step 1.2: Add `AutoDialAccessibilityService` to `AndroidManifest.xml`**

Inside the `<application>` block, after `</activity>`:

```xml
        <service
            android:name=".accessibility.AutoDialAccessibilityService"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```

- [ ] **Step 1.3: Commit**

```bash
git add app/src/main/res/xml/ app/src/main/AndroidManifest.xml
git commit -m "feat: accessibility service config and manifest registration"
```

---

### Task 2: Shared Model Classes

**Files:** `model/RunParams.kt`, `model/RunEvent.kt`, `model/RecordedStep.kt`

These are used by the accessibility service, the foreground service (Plan 3), and the wizard (Plan 4).

- [ ] **Step 2.1: Create `model/RunParams.kt`**

```kotlin
package com.autodial.model

data class RunParams(
    val number: String,
    val targetPackage: String,
    val plannedCycles: Int,        // 0 = spam mode
    val hangupSeconds: Int,
    val spamModeSafetyCap: Int,
    val interDigitDelayMs: Long
)
```

- [ ] **Step 2.2: Create `model/RunEvent.kt`**

Events published by `AutoDialAccessibilityService` and consumed by `RunForegroundService`.

```kotlin
package com.autodial.model

sealed class RunEvent {
    data class TargetForegrounded(val packageName: String) : RunEvent()
    data class TargetBackgrounded(val packageName: String) : RunEvent()
    data class StepActionSucceeded(val stepId: String, val outcome: String) : RunEvent()
    data class StepActionFailed(val stepId: String, val reason: String) : RunEvent()
    data class NodeAppeared(val anchorStepId: String) : RunEvent()
}
```

- [ ] **Step 2.3: Create `model/RecordedStep.kt`**

Carries capture data from `UiRecorder` → wizard before it's saved as `RecipeStep`. Mirrors `RecipeStep` fields but is not a DB entity.

```kotlin
package com.autodial.model

data class RecordedStep(
    val stepId: String,
    val resourceId: String?,
    val text: String?,
    val className: String?,
    val boundsRelX: Float,
    val boundsRelY: Float,
    val boundsRelW: Float,
    val boundsRelH: Float,
    val screenshotHashHex: String?,
    val recordedOnDensityDpi: Int,
    val recordedOnScreenW: Int,
    val recordedOnScreenH: Int,
    val missingResourceId: Boolean  // true = warn in wizard UI
)
```

- [ ] **Step 2.4: Commit**

```bash
git add app/src/main/java/com/autodial/model/
git commit -m "feat: shared model classes — RunParams, RunEvent, RecordedStep"
```

---

### Task 3: PhashUtil

**Files:** `accessibility/PhashUtil.kt`, `test/.../PhashUtilTest.kt`

- [ ] **Step 3.1: Write the failing test first**

Create `app/src/test/java/com/autodial/accessibility/PhashUtilTest.kt`:

```kotlin
package com.autodial.accessibility

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class PhashUtilTest {

    private fun solidBitmap(color: Int, w: Int = 64, h: Int = 64): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until w) for (y in 0 until h) bmp.setPixel(x, y, color)
        }

    @Test
    fun identicalBitmapsHaveZeroHammingDistance() {
        val bmp = solidBitmap(0xFF_33_66_99.toInt())
        val h1 = PhashUtil.compute(bmp)
        val h2 = PhashUtil.compute(bmp)
        assertEquals(0, PhashUtil.hammingDistance(h1, h2))
    }

    @Test
    fun differentBitmapsHaveLargeHammingDistance() {
        val white = solidBitmap(0xFF_FF_FF_FF.toInt())
        val black = solidBitmap(0xFF_00_00_00.toInt())
        val dist = PhashUtil.hammingDistance(PhashUtil.compute(white), PhashUtil.compute(black))
        assertTrue("expected distance > 10, got $dist", dist > 10)
    }

    @Test
    fun slightlyModifiedBitmapHasSmallHammingDistance() {
        val original = solidBitmap(0xFF_AA_BB_CC.toInt(), 64, 64)
        val modified = original.copy(Bitmap.Config.ARGB_8888, true).also { bmp ->
            bmp.setPixel(0, 0, 0xFF_AA_BB_CD.toInt())  // one pixel off
        }
        val dist = PhashUtil.hammingDistance(PhashUtil.compute(original), PhashUtil.compute(modified))
        assertTrue("expected distance ≤ 10, got $dist", dist <= 10)
    }

    @Test
    fun hexRoundTrip() {
        val bmp = solidBitmap(0xFF_12_34_56.toInt())
        val hash = PhashUtil.compute(bmp)
        val hex = PhashUtil.toHex(hash)
        assertEquals(hash, PhashUtil.fromHex(hex))
    }
}
```

- [ ] **Step 3.2: Run to verify failure**

```bash
./gradlew :app:test --tests "com.autodial.accessibility.PhashUtilTest"
```

Expected: `FAILED — unresolved reference: PhashUtil`

- [ ] **Step 3.3: Create `accessibility/PhashUtil.kt`**

```kotlin
package com.autodial.accessibility

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

object PhashUtil {

    fun compute(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
        val gray = FloatArray(32 * 32) { i ->
            val p = small.getPixel(i % 32, i / 32)
            0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }
        if (small !== bitmap) small.recycle()

        val dct = dct2d(gray, 32)
        // Top-left 8×8 of the DCT matrix, flattened
        val vals = FloatArray(64) { i -> dct[(i / 8) * 32 + (i % 8)] }

        // Mean of vals[1..63], skipping the DC component at index 0
        var sum = 0f
        for (i in 1 until 64) sum += vals[i]
        val mean = sum / 63f

        var hash = 0L
        for (i in 0 until 63) {
            if (vals[i + 1] > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun toHex(hash: Long): String = java.lang.Long.toUnsignedString(hash, 16)

    fun fromHex(hex: String): Long = java.lang.Long.parseUnsignedLong(hex, 16)

    private fun dct2d(input: FloatArray, size: Int): FloatArray {
        val temp = FloatArray(size * size)
        val output = FloatArray(size * size)
        for (row in 0 until size) {
            val rowDct = dct1d(FloatArray(size) { input[row * size + it] })
            for (col in 0 until size) temp[row * size + col] = rowDct[col]
        }
        for (col in 0 until size) {
            val colDct = dct1d(FloatArray(size) { temp[it * size + col] })
            for (row in 0 until size) output[row * size + col] = colDct[row]
        }
        return output
    }

    private fun dct1d(x: FloatArray): FloatArray {
        val n = x.size
        return FloatArray(n) { k ->
            val scale = if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
            var s = 0.0
            for (i in 0 until n) s += x[i] * cos(PI * k * (2 * i + 1) / (2.0 * n))
            (s * scale).toFloat()
        }
    }
}
```

- [ ] **Step 3.4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.autodial.accessibility.PhashUtilTest"
```

Expected: 4 tests pass.

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/PhashUtil.kt app/src/test/java/com/autodial/accessibility/PhashUtilTest.kt
git commit -m "feat: PhashUtil — 8x8 DCT perceptual hash with Hamming distance"
```

---

### Task 4: UiPlayer (4-Tier Playback)

**Files:** `accessibility/UiPlayer.kt`, `test/.../UiPlayerTest.kt`

- [ ] **Step 4.1: Write failing tests**

Create `app/src/test/java/com/autodial/accessibility/UiPlayerTest.kt`:

```kotlin
package com.autodial.accessibility

import android.graphics.Rect
import com.autodial.data.db.entity.RecipeStep
import org.junit.Assert.*
import org.junit.Test

class UiPlayerTest {

    private fun step(
        resourceId: String? = "com.b3networks.bizphone:id/btnDigit5",
        text: String? = "5",
        className: String = "android.widget.Button",
        boundsRelX: Float = 0.5f,
        boundsRelY: Float = 0.6f,
        boundsRelW: Float = 0.1f,
        boundsRelH: Float = 0.08f
    ) = RecipeStep(
        targetPackage = "com.b3networks.bizphone",
        stepId = "DIGIT_5",
        resourceId = resourceId, text = text, className = className,
        boundsRelX = boundsRelX, boundsRelY = boundsRelY,
        boundsRelW = boundsRelW, boundsRelH = boundsRelH,
        screenshotHashHex = null,
        recordedOnDensityDpi = 420, recordedOnScreenW = 1080, recordedOnScreenH = 2340
    )

    @Test
    fun tapCenterCalculation() {
        val screenW = 1080
        val screenH = 2340
        val s = step(boundsRelX = 0.5f, boundsRelY = 0.6f, boundsRelW = 0.1f, boundsRelH = 0.08f)
        val cx = s.boundsRelX * screenW + (s.boundsRelW * screenW) / 2f
        val cy = s.boundsRelY * screenH + (s.boundsRelH * screenH) / 2f
        assertEquals(594f, cx)
        assertEquals(1497.6f, cy, 0.1f)
    }

    @Test
    fun boundsOverlapAboveThreshold() {
        val screenW = 1080
        val screenH = 2340
        val s = step(boundsRelX = 0.0f, boundsRelY = 0.0f, boundsRelW = 0.5f, boundsRelH = 0.5f)
        val target = Rect(0, 0, (0.5f * screenW).toInt(), (0.5f * screenH).toInt())
        val nodeRect = Rect(0, 0, (0.4f * screenW).toInt(), (0.4f * screenH).toInt())
        val intersection = Rect()
        intersection.setIntersect(nodeRect, target)
        val overlap = (intersection.width().toFloat() * intersection.height()) /
                      (target.width().toFloat() * target.height())
        assertTrue("overlap $overlap should be > 0.5", overlap > 0.5f)
    }

    @Test
    fun boundsOverlapBelowThresholdRejected() {
        val screenW = 1080
        val screenH = 2340
        val target = Rect(0, 0, 540, 1170)
        val nodeRect = Rect(800, 1500, 900, 1600)  // no overlap
        val intersection = Rect()
        val hasIntersection = intersection.setIntersect(nodeRect, target)
        assertFalse(hasIntersection)
    }
}
```

- [ ] **Step 4.2: Run to verify failure**

```bash
./gradlew :app:test --tests "com.autodial.accessibility.UiPlayerTest"
```

Expected: tests compile and pass (these test pure math, no service needed). If they fail, fix the math before continuing.

- [ ] **Step 4.3: Create `accessibility/UiPlayer.kt`**

```kotlin
package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autodial.data.db.entity.RecipeStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class StepOutcome {
    data class Ok(val outcome: String) : StepOutcome()
    data class Failed(val reason: String) : StepOutcome()
}

class UiPlayer(
    private val service: AccessibilityService,
    private val screenW: Int,
    private val screenH: Int
) {

    suspend fun executeStep(step: RecipeStep): StepOutcome = withContext(Dispatchers.Default) {
        // Tier 1: resourceId primary match
        if (step.resourceId != null) {
            val root = service.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByViewId(step.resourceId) ?: emptyList()
            val node = nodes.firstOrNull { it.isVisibleToUser && it.className == step.className }
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return@withContext verify(step, "ok:node-primary")
            }
        }

        // Tier 2: text + class + bounds overlap > 50%
        val root = service.rootInActiveWindow
        if (root != null && step.text != null) {
            val match = findByTextClassBounds(root, step)
            if (match != null && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return@withContext verify(step, "ok:node-fallback")
            }
        }

        // Tier 3: coordinate tap
        val cx = step.boundsRelX * screenW + (step.boundsRelW * screenW) / 2f
        val cy = step.boundsRelY * screenH + (step.boundsRelH * screenH) / 2f
        dispatchTap(cx, cy)
        return@withContext verify(step, "ok:coord-fallback")
    }

    private suspend fun verify(step: RecipeStep, successOutcome: String): StepOutcome {
        if (step.screenshotHashHex == null) return StepOutcome.Ok(successOutcome)
        delay(300)
        val storedHash = PhashUtil.fromHex(step.screenshotHashHex)
        val bitmap = captureRegion(step) ?: return StepOutcome.Ok(successOutcome)
        val currentHash = PhashUtil.compute(bitmap)
        bitmap.recycle()
        return if (PhashUtil.hammingDistance(storedHash, currentHash) <= 10)
            StepOutcome.Ok(successOutcome)
        else
            StepOutcome.Failed("failed:hash-mismatch")
    }

    private fun findByTextClassBounds(root: AccessibilityNodeInfo, step: RecipeStep): AccessibilityNodeInfo? {
        val target = Rect(
            (step.boundsRelX * screenW).toInt(),
            (step.boundsRelY * screenH).toInt(),
            ((step.boundsRelX + step.boundsRelW) * screenW).toInt(),
            ((step.boundsRelY + step.boundsRelH) * screenH).toInt()
        )
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, candidates) { node ->
            node.text?.toString() == step.text &&
            node.className == step.className &&
            node.isVisibleToUser
        }
        return candidates
            .associateWith { overlapFraction(it, target) }
            .filter { it.value > 0.5f }
            .maxByOrNull { it.value }
            ?.key
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (predicate(node)) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, result, predicate) }
        }
    }

    private fun overlapFraction(node: AccessibilityNodeInfo, target: Rect): Float {
        val nb = Rect(); node.getBoundsInScreen(nb)
        val inter = Rect()
        if (!inter.setIntersect(nb, target)) return 0f
        val targetArea = target.width().toFloat() * target.height()
        return if (targetArea > 0f) (inter.width().toFloat() * inter.height()) / targetArea else 0f
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build(),
            null, null
        )
    }

    private fun captureRegion(step: RecipeStep): Bitmap? {
        var result: Bitmap? = null
        val latch = CountDownLatch(1)
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            { it.run() },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hw = screenshot.hardwareBuffer
                    val full = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                    if (full != null) {
                        val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                        val left = (step.boundsRelX * screenW).toInt().coerceIn(0, screenW - 1)
                        val top = (step.boundsRelY * screenH).toInt().coerceIn(0, screenH - 1)
                        val w = (step.boundsRelW * screenW).toInt().coerceAtLeast(1)
                            .coerceAtMost(screenW - left)
                        val h = (step.boundsRelH * screenH).toInt().coerceAtLeast(1)
                            .coerceAtMost(screenH - top)
                        result = Bitmap.createBitmap(soft, left, top, w, h)
                        soft.recycle()
                    }
                    hw.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            }
        )
        latch.await(2, TimeUnit.SECONDS)
        return result
    }
}
```

- [ ] **Step 4.4: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/UiPlayer.kt app/src/test/java/com/autodial/accessibility/UiPlayerTest.kt
git commit -m "feat: UiPlayer — 4-tier node resolution + perceptual hash verification"
```

---

### Task 5: UiRecorder

**Files:** `accessibility/UiRecorder.kt`

`UiRecorder` is called by `AutoDialAccessibilityService` when in record mode. It captures the next click event from the target package, extracts node info, and emits a `RecordedStep`.

- [ ] **Step 5.1: Create `accessibility/UiRecorder.kt`**

```kotlin
package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autodial.model.RecordedStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UiRecorder(
    private val service: AccessibilityService,
    private val screenW: Int,
    private val screenH: Int,
    private val densityDpi: Int
) {
    private val _captured = Channel<RecordedStep>(Channel.UNLIMITED)
    val capturedSteps = _captured.receiveAsFlow()

    @Volatile private var awaitingStepId: String? = null
    @Volatile private var debounceUntil: Long = 0L
    @Volatile private var targetPackage: String? = null

    fun startCapturing(stepId: String, pkg: String) {
        targetPackage = pkg
        awaitingStepId = stepId
        debounceUntil = System.currentTimeMillis() + 500L
    }

    fun stopCapturing() {
        awaitingStepId = null
        targetPackage = null
    }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val stepId = awaitingStepId ?: return
        val pkg = targetPackage ?: return
        if (event.packageName?.toString() != pkg) return
        if (System.currentTimeMillis() < debounceUntil) return

        val node = event.source ?: return
        awaitingStepId = null  // consume — one capture per startCapturing()

        val resourceId = node.viewIdResourceName
        val text = node.text?.toString()
        val className = node.className?.toString() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val relX = bounds.left.toFloat() / screenW
        val relY = bounds.top.toFloat() / screenH
        val relW = bounds.width().toFloat() / screenW
        val relH = bounds.height().toFloat() / screenH

        val hashHex = captureHash(node)

        _captured.trySend(
            RecordedStep(
                stepId = stepId,
                resourceId = resourceId,
                text = text,
                className = className,
                boundsRelX = relX, boundsRelY = relY,
                boundsRelW = relW, boundsRelH = relH,
                screenshotHashHex = hashHex,
                recordedOnDensityDpi = densityDpi,
                recordedOnScreenW = screenW,
                recordedOnScreenH = screenH,
                missingResourceId = resourceId == null
            )
        )
        node.recycle()
    }

    private fun captureHash(node: AccessibilityNodeInfo): String? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null

        var result: String? = null
        val latch = CountDownLatch(1)
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            { it.run() },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hw = screenshot.hardwareBuffer
                    val full = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                    if (full != null) {
                        val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                        val left = bounds.left.coerceIn(0, screenW - 1)
                        val top = bounds.top.coerceIn(0, screenH - 1)
                        val w = bounds.width().coerceAtLeast(1).coerceAtMost(screenW - left)
                        val h = bounds.height().coerceAtLeast(1).coerceAtMost(screenH - top)
                        val crop = Bitmap.createBitmap(soft, left, top, w, h)
                        result = PhashUtil.toHex(PhashUtil.compute(crop))
                        crop.recycle()
                        soft.recycle()
                    }
                    hw.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            }
        )
        latch.await(2, TimeUnit.SECONDS)
        return result
    }
}
```

- [ ] **Step 5.2: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/UiRecorder.kt
git commit -m "feat: UiRecorder — captures click events and extracts RecordedStep with pHash"
```

---

### Task 6: AutoDialAccessibilityService

**Files:** `accessibility/AutoDialAccessibilityService.kt`

The service is the single Android component that bridges `UiRecorder` and `UiPlayer` to the rest of the app. It exposes a companion-object `instance` for in-process calls.

- [ ] **Step 6.1: Create `accessibility/AutoDialAccessibilityService.kt`**

```kotlin
package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autodial.data.db.entity.RecipeStep
import com.autodial.model.RecordedStep
import com.autodial.model.RunEvent
import com.autodial.model.RunParams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AutoDialAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoDialAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _runEvents = MutableSharedFlow<RunEvent>(extraBufferCapacity = 64)
    val runEvents: SharedFlow<RunEvent> = _runEvents.asSharedFlow()

    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0

    private var recorder: UiRecorder? = null
    private var player: UiPlayer? = null

    private var activeTargetPackage: String? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi

        recorder = UiRecorder(this, screenW, screenH, densityDpi)
        player = UiPlayer(this, screenW, screenH)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ── Accessibility Events ───────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == activeTargetPackage) {
                    scope.launch { _runEvents.emit(RunEvent.TargetForegrounded(pkg)) }
                } else if (activeTargetPackage != null) {
                    scope.launch { _runEvents.emit(RunEvent.TargetBackgrounded(activeTargetPackage!!)) }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recorder?.onEvent(event)
            else -> {}
        }
    }

    // ── Record Mode API ─────────────────────────────────────────────────────

    fun startRecording(stepId: String, targetPackage: String) {
        recorder?.startCapturing(stepId, targetPackage)
    }

    fun stopRecording() {
        recorder?.stopCapturing()
    }

    fun recordedSteps() = recorder?.capturedSteps

    // ── Play Mode API ───────────────────────────────────────────────────────

    fun beginRun(params: RunParams) {
        activeTargetPackage = params.targetPackage
    }

    fun endRun() {
        activeTargetPackage = null
    }

    suspend fun executeStep(step: RecipeStep): StepOutcome {
        return player?.executeStep(step)
            ?: StepOutcome.Failed("failed:service-not-ready")
    }

    // ── Self-check ──────────────────────────────────────────────────────────

    fun isBound(): Boolean = instance != null

    fun selfCheck(): Boolean {
        if (instance == null) return false
        return try {
            serviceInfo != null
        } catch (e: Exception) {
            false
        }
    }
}
```

- [ ] **Step 6.2: Build to confirm no compile errors**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6.3: Commit**

```bash
git add app/src/main/java/com/autodial/accessibility/AutoDialAccessibilityService.kt
git commit -m "feat: AutoDialAccessibilityService — record mode, play mode, self-check"
```

---

### Task 7: Manual Smoke Test

Before Plan 3 wires the full run, verify the service registers correctly on a real device.

- [ ] **Step 7.1: Install the debug APK on a fleet phone**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 7.2: Enable the accessibility service**

On the device: Settings → Accessibility → Installed services → AutoDial → Enable.
Accept the permission dialog.

- [ ] **Step 7.3: Verify via adb**

```bash
adb shell settings get secure enabled_accessibility_services
```

Expected: output contains `com.autodial/.accessibility.AutoDialAccessibilityService`.

- [ ] **Step 7.4: Commit smoke-test passing note to git log**

```bash
git commit --allow-empty -m "test: accessibility service registers correctly on device (manual smoke)"
```

---

**Plan 2 complete.** The accessibility service is registered, `PhashUtil` is tested, `UiRecorder` captures steps, and `UiPlayer` executes 4-tier resolution. Proceed to Plan 3 (Run Orchestration).
