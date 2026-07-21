package com.example.customwallpaper.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.customwallpaper.ScheduleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("UNUSED_PARAMETER", "ktlint:standard:function-naming")
@Composable
fun WallpaperCropEditorScreen(
    viewModel: ScheduleViewModel,
    imageUri: Uri,
    targetSlot: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    var imageDimensions by remember { mutableStateOf<Size?>(null) }
    var rotationDegrees by remember { mutableStateOf(0f) }
    // Decoded bitmap is held in state for the lifetime of this composition. EXIF rotation
    // is applied at decode time (baked into the pixel data) rather than via
    // graphicsLayer(rotationZ). This ensures the layout math operates on correctly-oriented
    // dimensions so the preview fills the screen without overflowing on all sides.
    var decodedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                context.contentResolver.openInputStream(imageUri).use { inputStream ->
                    if (inputStream != null) {
                        BitmapFactory.decodeStream(inputStream, null, options)
                    } else {
                        android.util.Log.e(
                            "CropEditorScreen",
                            "Could not open input stream for Uri: $imageUri",
                        )
                        return@withContext
                    }
                }
                val rotation =
                    context.contentResolver.openInputStream(imageUri).use { inputStream ->
                        if (inputStream != null) {
                            val exif = androidx.exifinterface.media.ExifInterface(inputStream)
                            val orientation =
                                exif.getAttributeInt(
                                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                                )
                            when (orientation) {
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                        } else {
                            0f
                        }
                    }
                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                if (srcWidth <= 0 || srcHeight <= 0) {
                    android.util.Log.e(
                        "CropEditorScreen",
                        "Invalid image dimensions: ${srcWidth}x$srcHeight",
                    )
                    return@withContext
                }
                // Subsample high-res sources before allocating pixels. This mirrors
                // WallpaperBaker.kt's inSampleSize calculation so the preview and the
                // bake decode through identical code paths (design-crop-pan-fix.md §5
                // decode-path-parity invariant).
                val metrics = context.resources.displayMetrics
                val displayW = metrics.widthPixels
                val displayH = metrics.heightPixels
                var sampleSize = 1
                if (srcWidth > displayW || srcHeight > displayH) {
                    val isRotated = rotation == 90f || rotation == 270f
                    val effW = if (isRotated) srcHeight else srcWidth
                    val effH = if (isRotated) srcWidth else srcHeight
                    val halfWidth = effW / 2
                    val halfHeight = effH / 2
                    // For rotated images, any oversize dimension triggers subsampling
                    // because the rotate-copy step doubles peak memory.
                    val continueSubsample: (Int) -> Boolean = { s ->
                        val wOk = (halfWidth / s) >= displayW
                        val hOk = (halfHeight / s) >= displayH
                        if (isRotated) wOk || hOk else wOk && hOk
                    }
                    while (continueSubsample(sampleSize)) {
                        sampleSize *= 2
                    }
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                val bitmap =
                    context.contentResolver.openInputStream(imageUri).use { inputStream ->
                        if (inputStream != null) {
                            BitmapFactory.decodeStream(inputStream, null, options)
                        } else {
                            null
                        }
                    }

                // Apply EXIF rotation to the bitmap data so the pixel array is correctly
                // oriented. This eliminates the need for graphicsLayer(rotationZ) and
                // ensures the layout math uses the effective display dimensions, preventing
                // the rotated image from overflowing the screen on all sides.
                val finalBitmap =
                    if (bitmap != null && rotation != 0f) {
                        val rotMatrix = Matrix().apply { postRotate(rotation) }
                        val rotated =
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                bitmap.width,
                                bitmap.height,
                                rotMatrix,
                                true,
                            )
                        bitmap.recycle()
                        rotated
                    } else {
                        bitmap
                    }

                if (finalBitmap != null) {
                    imageDimensions =
                        Size(
                            finalBitmap.width.toFloat(),
                            finalBitmap.height.toFloat(),
                        )
                }
                rotationDegrees = 0f

                // Guard against the LaunchedEffect being cancelled (URI change or exit)
                // between decode and state assignment: if cancelled, recycle the bitmap
                // ourselves because DisposableEffect.onDispose will not run for a value
                // that was never published to decodedBitmap.
                if (coroutineContext.isActive) {
                    decodedBitmap = finalBitmap
                } else {
                    finalBitmap?.recycle()
                }
            } catch (e: Exception) {
                android.util.Log.e("CropEditorScreen", "Error reading image bounds", e)
            }
        }
    }

    // Release the decoded bitmap when the image URI changes or the composition exits
    // so we do not leak native pixel memory across navigations.
    DisposableEffect(imageUri) {
        onDispose {
            decodedBitmap?.recycle()
            decodedBitmap = null
        }
    }

    fun applyCrop(target: String) {
        isProcessing = true
        scope.launch {
            val resultFile =
                withContext(Dispatchers.IO) {
                    try {
                        com.example.customwallpaper.wallpaperscheduler.pipeline.bakeWallpaperFromUri(
                            context = context,
                            sourceUri = imageUri,
                            scale = scale,
                            offsetX = offset.x,
                            offsetY = offset.y,
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("CropEditorScreen", "Baking failed with exception", e)
                        null
                    }
                }

            isProcessing = false
            if (resultFile != null) {
                val path = resultFile.absolutePath
                viewModel.sessionTempFiles.add(path)

                when (target) {
                    "home" -> {
                        viewModel.homeWallpaperPathState.value = path
                    }
                    "lock" -> {
                        viewModel.lockWallpaperPathState.value = path
                    }
                    "both" -> {
                        viewModel.homeWallpaperPathState.value = path
                        viewModel.lockWallpaperPathState.value = path
                    }
                }
                onNavigateBack()
            } else {
                Toast.makeText(
                    context,
                    "Failed to process and crop image. Ensure it is a valid format and try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Read decodedBitmap at the top-level scope so any state change here triggers
    // a recomposition of the entire composable (BoxWithConstraints uses
    // SubcomposeLayout, and reads inside its content lambda were not reliably
    // invalidating in instrumented tests). Reading at function scope guarantees
    // recomposition propagates into the BoxWithConstraints content.
    val currentBitmap = decodedBitmap

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        // Get screen dimensions in pixels (matches WallpaperBaker's DisplayMetrics approach)
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        if (imageDimensions == null || currentBitmap == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val dimensions = imageDimensions!!
            // EXIF rotation is already applied to the bitmap data at decode time, so
            // dimensions reflect the correct display orientation. baseScale is computed
            // from these correctly-oriented dimensions, making the preview fill the screen
            // in one dimension (aspect-fill) and overflow only in the other.
            val baseScale = maxOf(screenWidth / dimensions.width, screenHeight / dimensions.height)
            val coverWpx = dimensions.width * baseScale
            val coverHpx = dimensions.height * baseScale

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(coverWpx, coverHpx, screenWidth, screenHeight) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1.0f, 5.0f)
                                val ts = baseScale * newScale
                                val sw = dimensions.width * ts
                                val sh = dimensions.height * ts
                                val mx = maxOf(0f, (sw - screenWidth) / 2f)
                                val my = maxOf(0f, (sh - screenHeight) / 2f)
                                scale = newScale
                                offset =
                                    Offset(
                                        x = (offset.x + pan.x).coerceIn(-mx, mx),
                                        y = (offset.y + pan.y).coerceIn(-my, my),
                                    )
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = BitmapPainter(currentBitmap.asImageBitmap()),
                    contentDescription = "Original wallpaper image",
                    modifier =
                        Modifier
                            .requiredSize(
                                width = with(LocalDensity.current) { coverWpx.toDp() },
                                height = with(LocalDensity.current) { coverHpx.toDp() },
                            )
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Top Layer UI - Instructions and Confirmation Buttons.
        // Rendered unconditionally (independent of decode progress) so the chrome is
        // always visible; only the image area waits on decodedBitmap above. This
        // mirrors the original AsyncImage-based layout where the button overlay
        // rendered before any decode completed.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Pinch to zoom, drag to pan inside the box",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Cancel")
                    }

                    Button(
                        onClick = { showTargetDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Crop & Confirm")
                    }
                }
            }
        }

        // Processing Overlay
        if (isProcessing) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(text = "Cropping wallpaper...", color = Color.White)
                }
            }
        }

        // Target Confirmation Dialog
        if (showTargetDialog) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showTargetDialog = false }) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .padding(20.dp)
                                .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Choose Destination Slot",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        Button(
                            onClick = {
                                showTargetDialog = false
                                applyCrop("home")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Apply to Home Screen")
                        }

                        Button(
                            onClick = {
                                showTargetDialog = false
                                applyCrop("lock")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Apply to Lock Screen")
                        }

                        Button(
                            onClick = {
                                showTargetDialog = false
                                applyCrop("both")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Apply to Both Screens")
                        }

                        TextButton(
                            onClick = { showTargetDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "Cancel")
                        }
                    }
                }
            }
        }
    }
}
