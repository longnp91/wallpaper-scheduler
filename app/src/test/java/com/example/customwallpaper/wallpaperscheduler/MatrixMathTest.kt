package com.example.customwallpaper.wallpaperscheduler

import android.graphics.Matrix
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MatrixMathTest {
    private fun verifyCropMapping(
        originalWidth: Float,
        originalHeight: Float,
        screenWidth: Float,
        screenHeight: Float,
        inSampleSize: Int,
        userScale: Float,
        rotationDegrees: Float,
        rawOffsetX: Float,
        rawOffsetY: Float,
    ) {
        val subsampledWidth = originalWidth / inSampleSize
        val subsampledHeight = originalHeight / inSampleSize

        val isRotated = (rotationDegrees == 90f || rotationDegrees == 270f)
        val rotatedWidth = if (isRotated) subsampledHeight else subsampledWidth
        val rotatedHeight = if (isRotated) subsampledWidth else subsampledHeight

        // baseScale uses NON-rotated subsampled dims to match WallpaperBaker.kt's
        // baseScale (decode-path-parity invariant; see design-crop-pan-fix.md §5.2).
        // The rotated swap is retained only for scaledWidth/scaledHeight below, which
        // drives the offset clamp so post-rotation bounds are honored.
        val baseScale = maxOf(screenWidth / subsampledWidth, screenHeight / subsampledHeight)
        val adjustedScale = baseScale * userScale

        val scaledWidth = rotatedWidth * adjustedScale
        val scaledHeight = rotatedHeight * adjustedScale

        val maxOffsetX = maxOf(0f, (scaledWidth - screenWidth) / 2f)
        val maxOffsetY = maxOf(0f, (scaledHeight - screenHeight) / 2f)

        val clampedOffsetX = rawOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
        val clampedOffsetY = rawOffsetY.coerceIn(-maxOffsetY, maxOffsetY)

        // Build transformation matrix M (maps subsampled image space to viewport space)
        val matrix = Matrix()
        val initialDx = (screenWidth - subsampledWidth) / 2f
        val initialDy = (screenHeight - subsampledHeight) / 2f
        matrix.postTranslate(initialDx, initialDy)

        val canvasCenterX = screenWidth / 2f
        val canvasCenterY = screenHeight / 2f
        matrix.postScale(adjustedScale, adjustedScale, canvasCenterX, canvasCenterY)
        matrix.postRotate(rotationDegrees, canvasCenterX, canvasCenterY)
        matrix.postTranslate(clampedOffsetX, clampedOffsetY)

        // Inverse matrix M^-1 (maps viewport space to subsampled image space)
        val inverseMatrix = Matrix()
        val success = matrix.invert(inverseMatrix)
        assertTrue("Matrix inversion must succeed", success)

        // Viewport corners
        val corners =
            listOf(
                0f to 0f,
                screenWidth to 0f,
                0f to screenHeight,
                screenWidth to screenHeight,
            )

        val tempPts = FloatArray(2)
        val tolerance = 0.1f

        for ((vx, vy) in corners) {
            tempPts[0] = vx
            tempPts[1] = vy
            inverseMatrix.mapPoints(tempPts)

            val sx = tempPts[0]
            val sy = tempPts[1]

            // Convert back to original image space
            val ox = sx * inSampleSize
            val oy = sy * inSampleSize

            // The mapped coordinate must fall within original bounds [0, originalWidth] and [0, originalHeight]
            assertTrue(
                "Mapped X coordinate $ox must be >= 0 for viewport ($vx, $vy)",
                ox >= -tolerance,
            )
            assertTrue(
                "Mapped X coordinate $ox must be <= originalWidth $originalWidth for viewport ($vx, $vy)",
                ox <= originalWidth + tolerance,
            )
            assertTrue(
                "Mapped Y coordinate $oy must be >= 0 for viewport ($vx, $vy)",
                oy >= -tolerance,
            )
            assertTrue(
                "Mapped Y coordinate $oy must be <= originalHeight $originalHeight for viewport ($vx, $vy)",
                oy <= originalHeight + tolerance,
            )
        }
    }

    @Test
    fun testCropBoxMappingMatchesInput() {
        // Scenario 1: Identity/Standard Fit - Portrait viewport, Landscape high-res image
        verifyCropMapping(
            originalWidth = 3840f,
            originalHeight = 2160f,
            screenWidth = 1080f,
            screenHeight = 1920f,
            inSampleSize = 2,
            userScale = 1.0f,
            rotationDegrees = 0f,
            rawOffsetX = 0f,
            rawOffsetY = 0f,
        )

        // Scenario 2: Zoomed-in with Panning - Portrait viewport, Portrait image
        verifyCropMapping(
            originalWidth = 1080f,
            originalHeight = 1920f,
            screenWidth = 1080f,
            screenHeight = 1920f,
            inSampleSize = 1,
            userScale = 2.0f,
            rotationDegrees = 0f,
            rawOffsetX = 150f,
            rawOffsetY = -250f,
        )

        // Scenario 3: Zoomed-in, Rotated 90 degrees with Panning
        verifyCropMapping(
            originalWidth = 2000f,
            originalHeight = 1000f,
            screenWidth = 1080f,
            screenHeight = 1920f,
            inSampleSize = 1,
            userScale = 1.5f,
            rotationDegrees = 90f,
            rawOffsetX = -100f,
            rawOffsetY = 100f,
        )

        // Scenario 4: Rotated 270 degrees, with high sample size
        verifyCropMapping(
            originalWidth = 4000f,
            originalHeight = 3000f,
            screenWidth = 1200f,
            screenHeight = 800f,
            inSampleSize = 4,
            userScale = 3.0f,
            rotationDegrees = 270f,
            rawOffsetX = 800f,
            rawOffsetY = -500f,
        )
    }
}
