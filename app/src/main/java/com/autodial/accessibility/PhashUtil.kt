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
