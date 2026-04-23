package com.autodial.accessibility

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class PhashUtilTest {

    private fun solidBitmap(color: Int, w: Int = 64, h: Int = 64): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until w) for (y in 0 until h) bmp.setPixel(x, y, color)
        }

    private fun gradient(w: Int = 64, h: Int = 64): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until w) for (y in 0 until h) {
                val v = (x * 255 / w).coerceIn(0, 255)
                bmp.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
            }
        }

    private fun halfSplit(horizontal: Boolean, w: Int = 64, h: Int = 64): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until w) for (y in 0 until h) {
                val isWhite = if (horizontal) y < h / 2 else x < w / 2
                bmp.setPixel(x, y, if (isWhite) 0xFF_FF_FF_FF.toInt() else 0xFF_00_00_00.toInt())
            }
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
        // Left-white/right-black vs top-white/bottom-black — different DCT frequency axes
        val hSplit = halfSplit(horizontal = false)  // left white, right black
        val vSplit = halfSplit(horizontal = true)   // top white, bottom black
        val dist = PhashUtil.hammingDistance(PhashUtil.compute(hSplit), PhashUtil.compute(vSplit))
        assertTrue("expected distance > 10, got $dist", dist > 10)
    }

    @Test
    fun slightlyModifiedBitmapHasSmallHammingDistance() {
        // Same gradient with +5 brightness shift — same structure, tiny intensity change
        val original = gradient()
        val brightened = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until 64) for (y in 0 until 64) {
                val v = ((x * 255 / 64) + 5).coerceIn(0, 255)
                bmp.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
            }
        }
        val dist = PhashUtil.hammingDistance(PhashUtil.compute(original), PhashUtil.compute(brightened))
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
