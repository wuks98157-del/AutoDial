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
