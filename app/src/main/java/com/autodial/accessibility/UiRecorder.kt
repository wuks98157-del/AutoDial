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
