package com.autodial.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autodial.model.RecordedStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.ArrayDeque
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

    private val pendingStepIds = ArrayDeque<String>()
    @Volatile private var debounceUntil: Long = 0L
    @Volatile private var targetPackage: String? = null
    @Volatile private var digitAutoMode: Boolean = false

    fun startCapturing(stepId: String, pkg: String) {
        startCapturing(listOf(stepId), pkg, digitAutoMode = false)
    }

    fun startCapturing(stepIds: List<String>, pkg: String, digitAutoMode: Boolean = false) {
        synchronized(pendingStepIds) {
            pendingStepIds.clear()
            pendingStepIds.addAll(stepIds)
        }
        targetPackage = pkg
        this.digitAutoMode = digitAutoMode
        debounceUntil = System.currentTimeMillis() + 500L
        Log.i(TAG, "startCapturing pkg=$pkg steps=$stepIds digitAutoMode=$digitAutoMode")
    }

    fun stopCapturing() {
        synchronized(pendingStepIds) { pendingStepIds.clear() }
        targetPackage = null
        digitAutoMode = false
    }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = targetPackage ?: return
        if (event.packageName?.toString() != pkg) return
        if (System.currentTimeMillis() < debounceUntil) return
        // Short-circuit if nothing is expected — cheaper than polling the queue.
        synchronized(pendingStepIds) { if (pendingStepIds.isEmpty()) return }

        // Grab the node FIRST — if the view is already gone (tap triggered a window
        // transition, e.g. the call screen opens after PRESS_CALL), abort without
        // consuming a queue slot so the user can tap again.
        val node = event.source ?: return

        // In RECORD_DIGITS we try to label the recording by the actual digit the user
        // tapped (derived from node text / contentDescription / descendant text).
        // This makes recording order-independent. Falls back to queue order only
        // when the accessibility tree doesn't expose a readable digit label.
        var autoDetected = false
        val stepId: String? = if (digitAutoMode) {
            val digit = detectDigit(node)
            if (digit != null) {
                val id = "DIGIT_$digit"
                // remove() returns false if already captured — that's fine, wizard
                // treats a repeat tap as an overwrite of the prior recording.
                synchronized(pendingStepIds) { pendingStepIds.remove(id) }
                autoDetected = true
                id
            } else {
                synchronized(pendingStepIds) {
                    if (pendingStepIds.isEmpty()) null else pendingStepIds.poll()
                }
            }
        } else {
            synchronized(pendingStepIds) {
                if (pendingStepIds.isEmpty()) null else pendingStepIds.poll()
            }
        }
        if (stepId == null) { node.recycle(); return }

        val resourceId = node.viewIdResourceName
        val text = node.text?.toString()
        val className = node.className?.toString()
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val relX = bounds.left.toFloat() / screenW
        val relY = bounds.top.toFloat() / screenH
        val relW = bounds.width().toFloat() / screenW
        val relH = bounds.height().toFloat() / screenH

        val hashHex = captureHash(node)

        Log.i(TAG, "RECORD $stepId rid=$resourceId text='$text' cls=$className bounds=$bounds " +
            "rel=(${"%.3f".format(relX)},${"%.3f".format(relY)},${"%.3f".format(relW)}x${"%.3f".format(relH)}) " +
            "clickable=${node.isClickable} desc='${node.contentDescription}' autoDetect=$autoDetected")
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
                missingResourceId = resourceId == null,
                digitAutoDetected = autoDetected
            )
        )
        node.recycle()

        // Brief debounce before accepting the next tap in the sequence.
        debounceUntil = System.currentTimeMillis() + 250L
    }

    // Look for a single digit 0-9 in the tapped node's text, contentDescription,
    // or in the text/contentDescription of nearby descendants (some custom dial
    // pads put the "7" label in a child TextView while the parent is the hitbox).
    private fun detectDigit(node: AccessibilityNodeInfo): Int? {
        extractDigit(node)?.let { return it }
        // Breadth-first walk over descendants, bounded for safety.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && visited < 32) {
            val n = queue.removeFirst(); visited++
            extractDigit(n)?.let { return it }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    companion object {
        private const val TAG = "AutoDial"
    }

    private fun extractDigit(node: AccessibilityNodeInfo): Int? {
        node.text?.toString()?.trim()?.let { t ->
            if (t.length == 1 && t[0].isDigit()) return t[0].digitToInt()
        }
        node.contentDescription?.toString()?.trim()?.let { d ->
            if (d.length == 1 && d[0].isDigit()) return d[0].digitToInt()
            // Some apps use "Digit 7", "7 key", "key 7" — pick out a lone digit.
            val match = Regex("(?<!\\d)(\\d)(?!\\d)").find(d)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
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
