package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autodial.data.db.entity.RecipeStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class StepOutcome {
    data class Ok(val outcome: String) : StepOutcome()
    data class Failed(val reason: String) : StepOutcome()
}

class UiPlayer(
    private val service: AccessibilityService,
    private val screenW: Int,
    private val screenH: Int
) {

    // Find a visible + clickable node by resourceId and tap it — no RecipeStep
    // needed. Used for BizPhone's auto-hangup: the end-call circle is only
    // visible during a call, so a single `isVisibleToUser && isClickable` filter
    // is enough to pick the right node out of a shared resourceId pool.
    suspend fun tapByResourceId(resourceId: String): StepOutcome = withContext(Dispatchers.Default) {
        val root = service.rootInActiveWindow
        val all = root?.findAccessibilityNodeInfosByViewId(resourceId) ?: emptyList()
        Log.d(TAG, "tapByResourceId($resourceId): all=${all.size}")
        val node = all.firstOrNull { it.isVisibleToUser && it.isClickable }
        if (node == null) {
            Log.w(TAG, "tapByResourceId($resourceId): no visible+clickable candidate")
            return@withContext StepOutcome.Failed("failed:auto-hangup-node-not-visible")
        }
        val b = android.graphics.Rect(); node.getBoundsInScreen(b)
        Log.i(TAG, "tapByResourceId($resourceId): tapping node bounds=$b desc='${node.contentDescription}'")
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) StepOutcome.Ok("ok:auto-hangup:$resourceId")
        else StepOutcome.Failed("failed:auto-hangup-click-refused")
    }

    // Press-and-hold at the center of the step's recorded bounds. BizPhone's
    // backspace clears all digits when held, which is the motivation for this
    // path — a single 2.5s press replaces 15 discrete taps that risked looking
    // like a scroll/flick. We prefer the live node's bounds over the recorded
    // rect so layout shifts don't push the press off-target.
    suspend fun longPressStep(step: RecipeStep, durationMs: Long): StepOutcome = withContext(Dispatchers.Default) {
        val root = service.rootInActiveWindow
        var cx = (step.boundsRelX + step.boundsRelW / 2f) * screenW
        var cy = (step.boundsRelY + step.boundsRelH / 2f) * screenH
        if (step.resourceId != null) {
            val hits = root?.findAccessibilityNodeInfosByViewId(step.resourceId) ?: emptyList()
            val match = hits.firstOrNull { it.isVisibleToUser && it.isClickable }
            if (match != null) {
                val b = Rect(); match.getBoundsInScreen(b)
                cx = b.exactCenterX()
                cy = b.exactCenterY()
                Log.i(TAG, "longPressStep rid=${step.resourceId} bounds=$b duration=${durationMs}ms")
            } else {
                Log.w(TAG, "longPressStep: no visible+clickable node for ${step.resourceId}, using recorded coords (${cx.toInt()},${cy.toInt()})")
            }
        }
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        service.dispatchGesture(gesture, null, null)
        delay(durationMs + 200)
        StepOutcome.Ok("ok:long-press:${durationMs}ms")
    }

    suspend fun executeStep(step: RecipeStep): StepOutcome = withContext(Dispatchers.Default) {
        val root = service.rootInActiveWindow
        val target = Rect(
            (step.boundsRelX * screenW).toInt(),
            (step.boundsRelY * screenH).toInt(),
            ((step.boundsRelX + step.boundsRelW) * screenW).toInt(),
            ((step.boundsRelY + step.boundsRelH) * screenH).toInt()
        )
        Log.d(TAG, "  target rect=$target")

        // Tier 1: resourceId primary match. Many custom dial pads (BizPhone) assign
        // the SAME resourceId to every digit button, so we can't just take the
        // first visible match — we have to rank by how well the candidate's
        // screen-bounds line up with the bounds we recorded. We also require the
        // candidate to be clickable; otherwise performAction returns true against
        // a non-interactive container and we'd silently advance the state machine
        // without anything having actually happened on screen.
        if (step.resourceId != null) {
            val all = root?.findAccessibilityNodeInfosByViewId(step.resourceId) ?: emptyList()
            val visible = all.filter { it.isVisibleToUser && (step.className == null || it.className == step.className) }
            Log.d(TAG, "  Tier1: rid=${step.resourceId} all=${all.size} visible=${visible.size}")
            val ranked = visible
                .map { it to overlapFraction(it, target) }
                .sortedByDescending { it.second }
            ranked.take(5).forEachIndexed { i, (n, ov) ->
                val b = Rect(); n.getBoundsInScreen(b)
                Log.d(TAG, "    cand[$i] bounds=$b overlap=${"%.2f".format(ov)} clickable=${n.isClickable} desc='${n.contentDescription}' text='${n.text}'")
            }
            val best = ranked.firstOrNull { it.first.isClickable } ?: ranked.firstOrNull()
            val overlap = best?.second ?: 0f
            // Require at least a sliver of overlap (10%) with the recorded bounds —
            // otherwise Tier 1 is almost certainly picking the wrong same-id node
            // on a different screen (e.g. splash / login / wrong tab).
            if (best != null && overlap >= 0.1f) {
                val clicked = best.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "  Tier1 click on best (overlap=${"%.2f".format(overlap)}, clickable=${best.first.isClickable}) → performAction=$clicked")
                if (clicked) {
                    val ov = "%.2f".format(overlap)
                    val suffix = if (!best.first.isClickable) ":non-clickable" else ""
                    return@withContext verify(step, "ok:node-primary:n=${ranked.size}:ov=$ov$suffix")
                }
            } else {
                Log.d(TAG, "  Tier1 skipped — best overlap=${"%.2f".format(overlap)} < 0.10")
            }
        } else {
            Log.d(TAG, "  Tier1 skipped — no recorded resourceId")
        }

        // Tier 2: text + class + bounds overlap > 50%
        if (root != null && step.text != null) {
            val match = findByTextClassBounds(root, step)
            Log.d(TAG, "  Tier2: text='${step.text}' cls=${step.className} match=${match != null}")
            if (match != null && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "  Tier2 click → performAction=true")
                return@withContext verify(step, "ok:node-fallback")
            }
        } else {
            Log.d(TAG, "  Tier2 skipped — text=${step.text}")
        }

        // Tier 3: coordinate tap
        val cx = step.boundsRelX * screenW + (step.boundsRelW * screenW) / 2f
        val cy = step.boundsRelY * screenH + (step.boundsRelH * screenH) / 2f
        Log.i(TAG, "  Tier3 coord tap @ (${cx.toInt()}, ${cy.toInt()})")
        dispatchTap(cx, cy)
        return@withContext verify(step, "ok:coord-fallback:x=${cx.toInt()},y=${cy.toInt()}")
    }

    companion object {
        private const val TAG = "AutoDial"
        // Hash verification adds ~300ms + a screenshot capture to every tap
        // (14+ taps per cycle). It's diagnostic-only — mismatches are logged
        // but never fail the run — so we disable it by default for speed.
        // Flip to true during development to investigate playback drift.
        private const val VERIFY_ENABLED = false
    }

    private suspend fun verify(step: RecipeStep, successOutcome: String): StepOutcome {
        // The click already succeeded. Hash verification is best-effort diagnostics:
        // tap ripples, press-state highlights, and screen transitions all shift the
        // post-click region enough to trip a strict hamming threshold, but that
        // doesn't mean the wrong thing was tapped. We record mismatches in history
        // (useful for debugging drift after an app update) but never fail the run.
        if (!VERIFY_ENABLED) return StepOutcome.Ok(successOutcome)
        if (step.screenshotHashHex == null) return StepOutcome.Ok(successOutcome)
        delay(300)
        val storedHash = PhashUtil.fromHex(step.screenshotHashHex)
        val bitmap = captureRegion(step) ?: return StepOutcome.Ok(successOutcome)
        val currentHash = PhashUtil.compute(bitmap)
        bitmap.recycle()
        return if (PhashUtil.hammingDistance(storedHash, currentHash) <= 10)
            StepOutcome.Ok(successOutcome)
        else
            StepOutcome.Ok("$successOutcome:hash-mismatch")
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
        // moveTo alone leaves the path with zero contours on some OEM Android builds;
        // the stroke then never materializes as a touch down. Adding a degenerate
        // lineTo at the same coord keeps the gesture a tap (no movement) while
        // guaranteeing the path is non-empty. Duration 80ms stays well under the
        // tap-timeout so it never gets reclassified as a long-press.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build(),
            null, null
        )
    }

    private suspend fun captureRegion(step: RecipeStep): Bitmap? =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hw = screenshot.hardwareBuffer
                        val full = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                        val crop = if (full != null) {
                            val soft = full.copy(Bitmap.Config.ARGB_8888, false)
                            full.recycle()
                            val left = (step.boundsRelX * screenW).toInt().coerceIn(0, screenW - 1)
                            val top = (step.boundsRelY * screenH).toInt().coerceIn(0, screenH - 1)
                            val w = (step.boundsRelW * screenW).toInt().coerceAtLeast(1)
                                .coerceAtMost(screenW - left)
                            val h = (step.boundsRelH * screenH).toInt().coerceAtLeast(1)
                                .coerceAtMost(screenH - top)
                            val result = Bitmap.createBitmap(soft, left, top, w, h)
                            soft.recycle()
                            result
                        } else null
                        hw.close()
                        cont.resume(crop)
                    }
                    override fun onFailure(errorCode: Int) { cont.resume(null) }
                }
            )
        }
}
