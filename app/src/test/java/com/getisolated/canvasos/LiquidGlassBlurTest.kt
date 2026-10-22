package com.getisolated.canvasos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the CPU box blur behind the Liquid Glass: opaque output, flat colours unchanged, light spread and kept, and no overrun at the edges. */
class LiquidGlassBlurTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private val opaqueBlack = argb(255, 0, 0, 0)

    private fun r(p: Int) = (p shr 16) and 255
    private fun g(p: Int) = (p shr 8) and 255
    private fun b(p: Int) = p and 255
    private fun a(p: Int) = (p shr 24) and 255

    @Test fun aFlatPictureStaysFlatAndBecomesOpaque() {
        val c = argb(0x80, 40, 120, 200)
        val px = IntArray(7 * 5) { c }
        LiquidGlass.boxBlur(px, 7, 5, 2)
        assertTrue(px.all { it == argb(255, 40, 120, 200) })
    }

    @Test fun theAlphaOfTheSourceIsIgnoredAndTheOutputIsAlwaysOpaque() {
        val px = IntArray(16) { argb(it * 16, it * 10, 0, 255 - it) }
        LiquidGlass.boxBlur(px, 4, 4, 1)
        assertTrue(px.all { a(it) == 255 })
    }

    @Test fun aRadiusOfZeroOnlyForcesOpacity() {
        val px = intArrayOf(argb(10, 1, 2, 3), argb(20, 4, 5, 6), argb(30, 7, 8, 9), argb(40, 10, 11, 12))
        LiquidGlass.boxBlur(px, 2, 2, 0)
        assertArrayEquals(intArrayOf(argb(255, 1, 2, 3), argb(255, 4, 5, 6), argb(255, 7, 8, 9), argb(255, 10, 11, 12)), px)
    }

    @Test fun aSingleBrightPixelSpreadsOverItsNeighboursAndNoFurther() {
        val w = 7
        val h = 7
        val px = IntArray(w * h) { opaqueBlack }
        px[3 * w + 3] = argb(255, 255, 255, 255)
        LiquidGlass.boxBlur(px, w, h, 1)
        for (y in 0 until h) for (x in 0 until w) {
            val inBlock = x in 2..4 && y in 2..4
            val v = px[y * w + x]
            // 255 / 3 / 3 with the integer division of each pass.
            if (inBlock) assertEquals("($x,$y)", argb(255, 28, 28, 28), v) else assertEquals("($x,$y)", opaqueBlack, v)
        }
    }

    @Test fun eachChannelIsBlurredOnItsOwn() {
        val w = 5
        val px = IntArray(w) { opaqueBlack }
        px[2] = argb(255, 255, 0, 0)
        LiquidGlass.boxBlur(px, w, 1, 1)
        assertTrue(px.all { g(it) == 0 && b(it) == 0 })
        assertEquals(listOf(0, 85, 85, 85, 0), px.map { r(it) })
    }

    @Test fun theEdgeIsExtendedNotWrappedOrDarkened() {
        // A bright first column keeps its light at the border: the picture is clamped there, not padded with black.
        val w = 4
        val h = 1
        val px = IntArray(w) { opaqueBlack }
        px[0] = argb(255, 90, 90, 90)
        LiquidGlass.boxBlur(px, w, h, 1)
        // Window of three, the missing one repeating the edge: (90 + 90 + 0) / 3 = 60, then (90 + 0 + 0) / 3 = 30.
        assertEquals(listOf(60, 30, 0, 0), px.map { r(it) })
    }

    @Test fun lightIsKeptApartFromRounding() {
        val w = 12
        val h = 9
        val px = IntArray(w * h) { opaqueBlack }
        // Away from the edges, so nothing clamped is counted twice.
        px[4 * w + 6] = argb(255, 255, 255, 255)
        px[5 * w + 5] = argb(255, 255, 255, 255)
        val before = px.sumOf { r(it) }
        LiquidGlass.boxBlur(px, w, h, 1)
        val after = px.sumOf { r(it) }
        assertTrue("$before -> $after", after <= before && before - after < 2 * 9)
    }

    @Test fun aRadiusLargerThanThePictureStillWorks() {
        val px = intArrayOf(argb(255, 0, 0, 0), argb(255, 90, 90, 90), argb(255, 180, 180, 180))
        LiquidGlass.boxBlur(px, 3, 1, 10)
        // Every window is mostly the clamped edges, so each pixel lands between the darkest and the brightest.
        assertTrue(px.all { r(it) in 0..180 && a(it) == 255 })
    }

    @Test fun aOnePixelPictureIsItself() {
        val px = intArrayOf(argb(255, 12, 34, 56))
        LiquidGlass.boxBlur(px, 1, 1, 5)
        assertArrayEquals(intArrayOf(argb(255, 12, 34, 56)), px)
    }

    @Test fun blurringIsSymmetricForASymmetricPicture() {
        val w = 9
        val h = 9
        val px = IntArray(w * h) { opaqueBlack }
        px[4 * w + 4] = argb(255, 200, 200, 200)
        LiquidGlass.boxBlur(px, w, h, 2)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(r(px[y * w + x]), r(px[y * w + (w - 1 - x)]))
            assertEquals(r(px[y * w + x]), r(px[(h - 1 - y) * w + x]))
            assertEquals(r(px[y * w + x]), r(px[x * w + y]))
        }
    }

    @Test fun repeatedBlursNeverBrightenAnythingPastTheBrightestPixel() {
        val w = 10
        val h = 6
        val px = IntArray(w * h) { argb(255, (it * 37) % 256, (it * 11) % 256, (it * 5) % 256) }
        val max = px.maxOf { r(it) }
        repeat(3) { LiquidGlass.boxBlur(px, w, h, 2) }
        assertTrue(px.all { r(it) <= max })
        assertTrue(px.all { a(it) == 255 })
    }
}
