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
