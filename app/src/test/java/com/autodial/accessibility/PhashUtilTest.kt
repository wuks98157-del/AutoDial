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
