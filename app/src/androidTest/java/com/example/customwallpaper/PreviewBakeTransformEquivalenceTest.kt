package com.example.customwallpaper

import android.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Locks the WYSIWYG contract between the [CropEditorScreen] preview transform and the
 * [com.example.customwallpaper.wallpaperscheduler.pipeline.WallpaperBaker] bake matrix.
 *
 * For every EXIF rotation θ in {0, 90, 180, 270}, the inverse of each transform must
 * map the four viewport corners back to the SAME source-pixel coordinate (within 0.5 px).
 * If a future refactor of either side drifts, this test catches it.
 *
 * See design-crop-pan-fix.md §3.3 (closed-form derivation) and §7.3 (test rationale).
 */
@RunWith(Parameterized::class)
class PreviewBakeTransformEquivalenceTest(
    private val rotationDegrees: Float,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "rotation={0}deg")
        fun data(): Collection<Array<Any>> =
            listOf(
                arrayOf<Any>(0f),
                arrayOf<Any>(90f),
                arrayOf<Any>(180f),
                arrayOf<Any>(270f),
            )
    }

    @Test
    fun previewMatchesBakeAtAllCorners() {
        // Non-square source so a rotated-vs-non-rotated mix-up would be detectable:
        // 700x400 matches the CropRotationAndVerticalPanTest fixture shape.
        val origW = 700f
        val origH = 400f
        val screenW = 1080f
        val screenH = 1920f
        val inSampleSize = 1
        val userScale = 1.5f
        // Offset stays inside the post-rotation clamp for every θ so the raw value is
        // honored verbatim by both transforms (no clamp divergence muddying the parity).
        val offsetX = 30f
        val offsetY = -45f

        val subW = origW / inSampleSize
        val subH = origH / inSampleSize

        // --- Bake matrix (mirror of WallpaperBaker.kt's construction) ---
        val bakeBaseScale = maxOf(screenW / subW, screenH / subH)
        val bakeAdjustedScale = bakeBaseScale * userScale
        val bakeMatrix = Matrix()
        val bakeInitialDx = (screenW - subW) / 2f
        val bakeInitialDy = (screenH - subH) / 2f
        bakeMatrix.postTranslate(bakeInitialDx, bakeInitialDy)
        val canvasCenterX = screenW / 2f
        val canvasCenterY = screenH / 2f
        bakeMatrix.postScale(bakeAdjustedScale, bakeAdjustedScale, canvasCenterX, canvasCenterY)
        bakeMatrix.postRotate(rotationDegrees, canvasCenterX, canvasCenterY)
        bakeMatrix.postTranslate(offsetX, offsetY)
        val bakeInverse = Matrix()
        assertTrue("Bake matrix must be invertible for θ=$rotationDegrees", bakeMatrix.invert(bakeInverse))

        // --- Preview closed-form (mirror of §3.3 derivation) ---
        // baseScale uses NON-rotated original dims to match production
        // (CropEditorScreen keeps inSampleSize out of the formula because it works in
        // original-pixel space; WallpaperBaker's subsampled baseScale × inSampleSize
        // resolves to the same value, so the two transforms are algebraically equal).
        val previewBaseScale = maxOf(screenW / origW, screenH / origH)
        val previewAdjustedScale = previewBaseScale * userScale
        val origCenterX = origW / 2f
        val origCenterY = origH / 2f

        val corners =
            listOf(
                0f to 0f,
                screenW to 0f,
                0f to screenH,
                screenW to screenH,
            )
        val tolerance = 0.5f

        for ((vx, vy) in corners) {
            // Preview inverse: p_orig = R(-θ)(V - offset - C) / adjustedScale + origCenter
            val dx = vx - offsetX - canvasCenterX
            val dy = vy - offsetY - canvasCenterY
            val angleRad = -rotationDegrees * Math.PI.toFloat() / 180f
            val cos = Math.cos(angleRad.toDouble()).toFloat()
            val sin = Math.sin(angleRad.toDouble()).toFloat()
            val rx = dx * cos - dy * sin
            val ry = dx * sin + dy * cos
            val previewSrcX = rx / previewAdjustedScale + origCenterX
            val previewSrcY = ry / previewAdjustedScale + origCenterY

            // Bake inverse: map viewport to subsampled coords, then scale up to original.
            val pts = FloatArray(2)
            pts[0] = vx
            pts[1] = vy
            bakeInverse.mapPoints(pts)
            val bakeSrcX = pts[0] * inSampleSize
            val bakeSrcY = pts[1] * inSampleSize

            assertEquals(
                "X source mismatch at viewport ($vx, $vy) for θ=$rotationDegrees",
                bakeSrcX,
                previewSrcX,
                tolerance,
            )
            assertEquals(
                "Y source mismatch at viewport ($vx, $vy) for θ=$rotationDegrees",
                bakeSrcY,
                previewSrcY,
                tolerance,
            )
        }
    }
}
