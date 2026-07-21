package com.example.customwallpaper.wallpaperscheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropEditorMathTest {
    // Helper mapping function using the inverse matrix equations
    private fun mapViewportToOriginal(
        vx: Float,
        vy: Float,
        vw: Float,
        vh: Float,
        iw: Float,
        ih: Float,
        inSampleSize: Int,
        userScale: Float,
        rotationDegrees: Float,
        dx: Float,
        dy: Float,
    ): Pair<Float, Float> {
        val sw = iw / inSampleSize
        val sh = ih / inSampleSize

        val isRotated = (rotationDegrees == 90f || rotationDegrees == 270f)
        val rotatedWidth = if (isRotated) sh else sw
        val rotatedHeight = if (isRotated) sw else sh

        // baseScale uses NON-rotated subsampled dims to match WallpaperBaker.kt's
        // baseScale (decode-path-parity invariant; see design-crop-pan-fix.md §5.2).
        // The rotated swap is retained only for scaledWidth/scaledHeight below, which
        // drives the offset clamp so post-rotation bounds are honored.
        val baseScale = maxOf(vw / sw, vh / sh)
        val totalScale = baseScale * userScale

        val scaledWidth = rotatedWidth * totalScale
        val scaledHeight = rotatedHeight * totalScale

        val maxOffsetX = maxOf(0f, (scaledWidth - vw) / 2f)
        val maxOffsetY = maxOf(0f, (scaledHeight - vh) / 2f)

        val clampedDx = dx.coerceIn(-maxOffsetX, maxOffsetX)
        val clampedDy = dy.coerceIn(-maxOffsetY, maxOffsetY)

        // 1. Inverse user translation
        val x1 = vx - clampedDx
        val y1 = vy - clampedDy

        val cx = vw / 2f
        val cy = vh / 2f

        // 2. Inverse rotation
        val angleRad = -rotationDegrees * Math.PI.toFloat() / 180f
        val cos = Math.cos(angleRad.toDouble()).toFloat()
        val sin = Math.sin(angleRad.toDouble()).toFloat()
        val rx = (x1 - cx) * cos - (y1 - cy) * sin + cx
        val ry = (x1 - cx) * sin + (y1 - cy) * cos + cy

        // 3. Inverse scale
        val x3 = (rx - cx) / totalScale + cx
        val y3 = (ry - cy) / totalScale + cy

        // 4. Inverse initial centering
        val initialDx = (vw - sw) / 2f
        val initialDy = (vh - sh) / 2f
        val xs = x3 - initialDx
        val ys = y3 - initialDy

        // 5. Scale up to original
        return Pair(xs * inSampleSize, ys * inSampleSize)
    }

    @Test
    fun testIdentityTransform_CornersMapExactly() {
        val iw = 2000f
        val ih = 1000f
        val vw = 1000f
        val vh = 500f
        val inSampleSize = 2

        // Map Top-Left
        val (ox, oy) = mapViewportToOriginal(0f, 0f, vw, vh, iw, ih, inSampleSize, 1.0f, 0f, 0f, 0f)
        assertEquals(0f, ox, 0.1f)
        assertEquals(0f, oy, 0.1f)

        // Map Bottom-Right
        val (obrx, obry) = mapViewportToOriginal(vw, vh, vw, vh, iw, ih, inSampleSize, 1.0f, 0f, 0f, 0f)
        assertEquals(iw, obrx, 0.1f)
        assertEquals(ih, obry, 0.1f)
    }

    @Test
    fun testScaleAndOffset_CoordinatesClampWithinBounds() {
        val iw = 1920f
        val ih = 1080f
        val vw = 1080f
        val vh = 1920f
        val inSampleSize = 1

        val userScale = 2.0f
        val dx = 100f
        val dy = -150f

        // Map all 4 corners
        val corners =
            listOf(
                Pair(0f, 0f),
                Pair(vw, 0f),
                Pair(vw, vh),
                Pair(0f, vh),
            )

        for ((vx, vy) in corners) {
            val (ox, oy) = mapViewportToOriginal(vx, vy, vw, vh, iw, ih, inSampleSize, userScale, 0f, dx, dy)
            assertTrue("X coordinate $ox should be within [0, $iw]", ox in 0f..iw)
            assertTrue("Y coordinate $oy should be within [0, $ih]", oy in 0f..ih)
        }
    }

    @Test
    fun testScaleAndOffset_CoordinatesAreSuccessfullyClamped() {
        val iw = 2000f
        val ih = 1000f
        val vw = 1000f
        val vh = 500f
        val inSampleSize = 1

        val userScale = 2.0f
        // Out-of-bounds panning: shift image way to the right (dx = 10000f) and way up (dy = -10000f)
        val dx = 10000f
        val dy = -10000f

        // Since dx is clamped to maxOffsetX, the left edge of the viewport (vx = 0f) should map exactly to the left edge of the image (ox = 0f).
        // Since dy is clamped to -maxOffsetY, the bottom edge of the viewport (vy = vh = 500f) should map exactly to the bottom edge of the image (oy = ih = 1000f).
        val (oxLeft, oyBottom) = mapViewportToOriginal(0f, vh, vw, vh, iw, ih, inSampleSize, userScale, 0f, dx, dy)
        assertEquals(0f, oxLeft, 0.1f)
        assertEquals(ih, oyBottom, 0.1f)
    }
}
