package com.example.customwallpaper.wallpaperscheduler.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val TAG = "WallpaperBaker"

/**
 * Decodes, subsamples, transforms, and persists a wallpaper image from a source URI.
 * Safely processes high-resolution images by calculating appropriate downscaling and
 * applying EXIF orientation adjustments to prevent Out-Of-Memory exceptions.
 *
 * @param context The application context.
 * @param sourceUri The URI of the image to bake.
 * @param scale User-defined scale factor to apply on top of the base scale.
 * @param offsetX Horizontal translation offset specified by the user.
 * @param offsetY Vertical translation offset specified by the user.
 * @return A [File] pointing to the persisted JPEG image, or null if baking failed.
 */
fun bakeWallpaperFromUri(
    context: Context,
    sourceUri: Uri,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): File? {
    val contentResolver = context.contentResolver
    var subsampledBitmap: Bitmap? = null
    var outputBitmap: Bitmap? = null

    try {
        // Query dimensions first to avoid allocating heavy pixel arrays before we know the scale.
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        contentResolver.openInputStream(sourceUri).use { inputStream ->
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for Uri: $sourceUri")
                return null
            }
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            Log.e(TAG, "Invalid image dimensions decoded: ${srcWidth}x$srcHeight")
            return null
        }

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // ExifInterface requires its own stream since it reads metadata headers.
        val rotationDegrees =
            try {
                contentResolver.openInputStream(sourceUri).use { inputStream ->
                    if (inputStream != null) {
                        val exif = ExifInterface(inputStream)
                        val orientation =
                            exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } else {
                        0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read EXIF orientation, default to 0", e)
                0
            }

        // Downsample high-resolution images to a power-of-2 factor that exceeds screen size.
        // This acts as a primary defense against JVM OutOfMemoryErrors.
        // Account for EXIF rotation: the effective dimensions after rotation determine
        // the subsample size, ensuring rotated bitmaps fit in memory during the rotate pass.
        // For rotated images (which create a rotate-copy, doubling peak memory), any
        // oversize dimension triggers subsampling; for non-rotated, both must oversize.
        var inSampleSize = 1
        if (srcWidth > screenWidth || srcHeight > screenHeight) {
            val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
            val effW = if (isRotated) srcHeight else srcWidth
            val effH = if (isRotated) srcWidth else srcHeight
            val halfWidth = effW / 2
            val halfHeight = effH / 2
            while (true) {
                val wOk = (halfWidth / inSampleSize) >= screenWidth
                val hOk = (halfHeight / inSampleSize) >= screenHeight
                if (if (isRotated) !(wOk || hOk) else !(wOk && hOk)) break
                inSampleSize *= 2
            }
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize

        // Decode the subsampled bitmap into memory.
        subsampledBitmap =
            contentResolver.openInputStream(sourceUri).use { inputStream ->
                if (inputStream == null) {
                    Log.e(TAG, "Could not open input stream for decoding bitmap")
                    return null
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            }

        if (subsampledBitmap == null) {
            Log.e(TAG, "Failed to decode subsampled bitmap")
            return null
        }

        // Apply EXIF rotation to the bitmap data so the pixel array is correctly
        // oriented. This mirrors the CropEditorScreen's approach and eliminates the
        // need for matrix.postRotate() below, ensuring the baker and editor use
        // identical dimensions for baseScale calculation.
        if (rotationDegrees != 0) {
            val rotMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated =
                Bitmap.createBitmap(
                    subsampledBitmap,
                    0,
                    0,
                    subsampledBitmap.width,
                    subsampledBitmap.height,
                    rotMatrix,
                    true,
                )
            subsampledBitmap.recycle()
            subsampledBitmap = rotated
        }

        // Create the canvas backed by a screen-sized bitmap.
        outputBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Calculate aspect fill ratio so the wallpaper fits the entire screen area.
        val baseScale =
            maxOf(
                screenWidth.toFloat() / subsampledBitmap.width,
                screenHeight.toFloat() / subsampledBitmap.height,
            )
        val adjustedScale = baseScale * scale

        val matrix = Matrix()
        // Center the bitmap relative to the screen to establish a reliable transform origin.
        val initialDx = (screenWidth - subsampledBitmap.width) / 2f
        val initialDy = (screenHeight - subsampledBitmap.height) / 2f
        matrix.postTranslate(initialDx, initialDy)

        val canvasCenterX = screenWidth / 2f
        val canvasCenterY = screenHeight / 2f
        // Apply scaling and translation around the screen/bitmap center.
        // Rotation is already applied to the bitmap data at decode time.
        matrix.postScale(adjustedScale, adjustedScale, canvasCenterX, canvasCenterY)
        matrix.postTranslate(offsetX, offsetY)

        val paint =
            Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
        canvas.drawBitmap(subsampledBitmap, matrix, paint)

        // Persist the rendered canvas to a timestamped file in internal storage.
        val timestamp = System.currentTimeMillis()
        val targetFile = File(context.filesDir, "baked_wp_$timestamp.jpg")

        FileOutputStream(targetFile).use { outputStream ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }

        Log.d(
            TAG,
            "Baking success: file=${targetFile.name}, original=${srcWidth}x$srcHeight, " +
                "subsampled=${subsampledBitmap.width}x${subsampledBitmap.height}, " +
                "screen=${screenWidth}x$screenHeight, inSampleSize=$inSampleSize, " +
                "rotation=$rotationDegrees, scale=$scale, offsets=($offsetX, $offsetY)",
        )

        return targetFile
    } catch (e: Exception) {
        Log.e(TAG, "Failed to bake wallpaper from Uri: $sourceUri", e)
        return null
    } finally {
        // Explicitly recycle both bitmaps to reclaim native memory and ease GC pressure.
        subsampledBitmap?.recycle()
        outputBitmap?.recycle()
    }
}
