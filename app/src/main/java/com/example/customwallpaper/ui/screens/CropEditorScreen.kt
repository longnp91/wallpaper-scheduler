package com.example.customwallpaper.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.customwallpaper.ScheduleViewModel
import kotlinx.coroutines.Dispatchers
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

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

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

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        // Plane 1: Bottom Layer - Gesture-tracked Image
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5.0f)
                            offset = offset + pan
                        }
                    },
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Original wallpaper image",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                contentScale = ContentScale.Fit,
            )
        }

        // Plane 2: Middle Layer - Aspect Ratio Viewfinder Cutout
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            // Draw transparent grey background overlay
            drawRect(color = Color.Black.copy(alpha = 0.5f))

            val screenWidth = size.width
            val screenHeight = size.height

            // Viewfinder matches screen aspect ratio (80% size)
            val rectWidth = screenWidth * 0.8f
            val rectHeight = screenHeight * 0.8f
            val left = (screenWidth - rectWidth) / 2
            val top = (screenHeight - rectHeight) / 2

            // Cut out the center
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                blendMode = BlendMode.Clear,
            )

            // Draw a border around the cutout
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // Plane 3: Top Layer - Instructions and Confirmation Button
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
