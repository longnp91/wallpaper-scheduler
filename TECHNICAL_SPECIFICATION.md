# Technical Specification: Scheduled Custom Wallpaper Android Application

## 1. Project Scope & Architecture Overview
This document defines the architecture and implementation blueprint for a streamlined, privacy-focused Android wallpaper utility. The application bypasses the standard cloud-curated feeds of Google Wallpapers, focusing instead on two distinct user pillars:
1. **Dynamic Native Media Access:** Importing images seamlessly from local storage or cloud directories (Google Drive, OneDrive).
2. **Precision Image Personalization & Automated Scheduling:** Offering a high-fidelity editor UI (pan, zoom, crop) and an automated, low-overhead scheduling runner that cycles the wallpaper at customized intervals.

### Technology Stack Requirements
* **Language:** Kotlin (leveraging structured concurrency via Coroutines for blocking I/O)
* **UI Framework:** Jetpack Compose (Declarative layout engine)
* **Image Loading Engine:** Coil (Coroutines Image Loader for performance-optimized caching)
* **Background Scheduling:** Android Jetpack WorkManager
* **Minimum SDK Target:** API 33 (Android 13.0 - Tiramisu) or newer
* **Development Environment:** Android Studio running on Ubuntu Linux with KVM hardware virtualization enabled

---

## 2. Permissions & Manifest Definitions
The application requires explicit system clearance to manipulate device backgrounds and interact with network resources if cloud streaming links are used.

```xml
<manifest xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
    package="com.example.customwallpaper">

    <uses-permission android:name="android.permission.SET_WALLPAPER" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.Material3.DayNight">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Material3.DayNight">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
    </application>
</manifest>
```

---

## 3. Storage Ingestion Component (Local & Cloud Unified)
To avoid manual cloud provider API integration, the app utilizes the native Storage Access Framework (SAF). This presents a unified system picker displaying internal storage, Google Drive, and OneDrive seamlessly.

⚠️ Critical Architecture Constraint: Cloud provider document URIs are ephemeral. Their security flags expire after short periods. To allow the background execution task runner to access the files hours later, read permissions must be explicitly persisted to the OS layer.

```kotlin
// Ingestion Framework Setup within an Activity or Composable Context
val pickImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { secureUri ->
        try {
            // Persist read access across system reboots and long background durations
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(secureUri, takeFlags)
            
            // Route the verified token to the editing view
            navigateToEditingScreen(secureUri)
        } catch (e: SecurityException) {
            // Handle fallback execution for non-persistable files
            navigateToEditingScreen(secureUri)
        }
    }
}

// Triggering the Unified System Picker UI
Button(onClick = { pickImageLauncher.launch("image/*") }) {
    Text("Select Wallpaper Source")
}
```

---

## 4. UI Layer: Dual-Plane Viewfinder Canvas
The editing layer leverages a layered Box architecture. The lower canvas tracks matrix gesture adjustments (scale, offset), while the top layer applies a hardware-accelerated dark tint mask with a transparent window acting as a cropping guideline.

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

@Composable
fun WallpaperCropEditor(imageUri: Uri, onSaveTriggered: (Float, Offset) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        
        // PLANE 1: Interactive Touch Surface (Handles Scaling and Panning Translations)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Prevent downscaling past normal baseline dimensions
                        scale = (scale * zoom).coerceAtLeast(1f)
                        offset += pan
                    }
                }
        ) {
            Image(
                painter = rememberAsyncImagePainter(imageUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }

        // PLANE 2: Fixed Visual Mask Overlay (Viewfinder Grid/Window stencil)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val screenWidth = size.width
            val screenHeight = size.height
            
            // Standardizing a crop frame boundaries centered inside the viewport
            val cropWidth = screenWidth * 0.9f
            val cropHeight = screenHeight * 0.8f
            val left = (screenWidth - cropWidth) / 2
            val top = (screenHeight - cropHeight) / 2

            // Draw translucent backing fill
            drawRect(color = Color.Black.copy(alpha = 0.4f))

            // Cut out an explicitly clear center viewfinder window using Clear blend mode
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(cropWidth, cropHeight),
                blendMode = BlendMode.Clear
            )
        }

        // PLANE 3: Action Controls
        Text(
            text = "⛶ Move and scale",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp)
        )

        Button(
            onClick = { onSaveTriggered(scale, offset) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Text("Select image")
        }
    }
}
```

---

## 5. Media Rendering Pipeline ("Baking")
To decouple background operations from heavy math operations, image processing is executed upfront. When the user confirms their placement, the source data is drawn to an off-screen bitmap matching the native display dimensions and saved straight to local app files.

```kotlin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

fun bakeWallpaper(
    context: Context, 
    sourceBitmap: Bitmap, 
    scale: Float, 
    offsetX: Float, 
    offsetY: Float
): Bitmap {
    // Collect device physical dimensions
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels

    // Instantiate flat drawing canvas matching device screen aspect ratio
    val outputBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(outputBitmap)

    // Apply the spatial transformation matrix generated in the UI layer
    val matrix = Matrix().apply {
        postScale(scale, scale, sourceBitmap.width / 2f, sourceBitmap.height / 2f)
        postTranslate(offsetX, offsetY)
    }

    // Render configuration with bilinear filtering enabled for crisp output
    val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    canvas.drawBitmap(sourceBitmap, matrix, paint)
    
    return outputBitmap
}
```

---

## 6. Background Scheduling Engine (WorkManager Integration)
Background execution utilizes Jetpack WorkManager. By writing a discrete worker that tracks static local targets (context.filesDir), the system detaches completely from complex cloud dependencies during execution phases.

### Background Worker Subsystem
```kotlin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

fun bakeWallpaper(
    context: Context, 
    sourceBitmap: Bitmap, 
    scale: Float, 
    offsetX: Float, 
    offsetY: Float
): Bitmap {
    // Collect device physical dimensions
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels

    // Instantiate flat drawing canvas matching device screen aspect ratio
    val outputBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(outputBitmap)

    // Apply the spatial transformation matrix generated in the UI layer
    val matrix = Matrix().apply {
        postScale(scale, scale, sourceBitmap.width / 2f, sourceBitmap.height / 2f)
        postTranslate(offsetX, offsetY)
    }

    // Render configuration with bilinear filtering enabled for crisp output
    val paint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    canvas.drawBitmap(sourceBitmap, matrix, paint)
    
    return outputBitmap
}
```

### Scheduling Task Setup (Invoked via Main Settings UI)
```kotlin
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

fun enqueueWallpaperRotation(context: Context, intervalHours: Long) {
    // Establish recurring work requirements
    val rotationRequest = PeriodicWorkRequestBuilder<WallpaperRollWorker>(
        intervalHours, TimeUnit.HOURS
    ).build()

    // Enqueue task ensuring old pacing routines are replaced cleanly with the new rule
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "ApplicationWallpaperRotationQueue",
        ExistingPeriodicWorkPolicy.UPDATE,
        rotationRequest
    )
}
```
