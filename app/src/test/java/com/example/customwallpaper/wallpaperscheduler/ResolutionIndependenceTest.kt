package com.example.customwallpaper.wallpaperscheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionIndependenceTest {
    private fun mapViewportToOriginal(
        vx: Float,
        vy: Float,
        vw: Float,
        vh: Float,
        iw: Float,
        ih: Float,
        inSampleSize: Int,
        userScale: Float,
        dx: Float,
        dy: Float,
    ): Pair<Float, Float> {
        val sw = iw / inSampleSize
        val sh = ih / inSampleSize
        val baseScale = maxOf(vw / sw, vh / sh)
        val totalScale = baseScale * userScale

        val scaledWidth = sw * totalScale
        val scaledHeight = sh * totalScale

        val maxOffsetX = maxOf(0f, (scaledWidth - vw) / 2f)
        val maxOffsetY = maxOf(0f, (scaledHeight - vh) / 2f)

        val clampedDx = dx.coerceIn(-maxOffsetX, maxOffsetX)
        val clampedDy = dy.coerceIn(-maxOffsetY, maxOffsetY)

        val x1 = vx - clampedDx
        val y1 = vy - clampedDy
        val cx = vw / 2f
        val cy = vh / 2f

        val x3 = (x1 - cx) / totalScale + cx
        val y3 = (y1 - cy) / totalScale + cy

        val initialDx = (vw - sw) / 2f
        val initialDy = (vh - sh) / 2f

        return Pair((x3 - initialDx) * inSampleSize, (y3 - initialDy) * inSampleSize)
    }

    @Test
    fun testCropInvarianceForDifferentSampleSizes() {
        val iw = 3840f
        val ih = 2160f
        val vw = 1080f
        val vh = 1920f

        val userScale = 1.5f
        val dx = 50f
        val dy = -80f

        // Map corners for inSampleSize = 1
        val (ox1_1, oy1_1) = mapViewportToOriginal(0f, 0f, vw, vh, iw, ih, 1, userScale, dx, dy)
        val (ox2_1, oy2_1) = mapViewportToOriginal(vw, vh, vw, vh, iw, ih, 1, userScale, dx, dy)

        // Map corners for inSampleSize = 4
        val (ox1_4, oy1_4) = mapViewportToOriginal(0f, 0f, vw, vh, iw, ih, 4, userScale, dx, dy)
        val (ox2_4, oy2_4) = mapViewportToOriginal(vw, vh, vw, vh, iw, ih, 4, userScale, dx, dy)

        // Assert mathematical invariance (precision tolerance delta = 1.0f due to float rounding)
        assertEquals(ox1_1, ox1_4, 1.0f)
        assertEquals(oy1_1, oy1_4, 1.0f)
        assertEquals(ox2_1, ox2_4, 1.0f)
        assertEquals(oy2_1, oy2_4, 1.0f)
    }
}
