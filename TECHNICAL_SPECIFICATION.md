# Technical Specification: Scheduled Custom Wallpaper Android Application

## 1. Project Scope & Architecture Overview
This document defines the architecture and implementation blueprint for a streamlined, privacy-focused Android wallpaper utility. The application bypasses the standard cloud-curated feeds of Google Wallpapers, focusing instead on two distinct user pillars:
1. **Dynamic Native Media Access:** Importing images seamlessly from local storage or cloud directories (Google Drive, OneDrive) with persistent permissions.
2. **Duration-Based Scheduling & Target Screen Separation:** Defining time-duration schedules (`From`/`To` time ranges) independently targeting the **Home screen**, **Lock screen**, or **Both**. In unoccupied time slots (when no scheduled rule is active), the current system or lock screen wallpaper passively persists without enforcing any app-level default backgrounds.

### Technology Stack Requirements
* **Language:** Kotlin (leveraging structured concurrency via Coroutines for blocking I/O)
* **UI Framework:** Jetpack Compose (Declarative layout engine)
* **Image Loading Engine:** Coil (Coroutines Image Loader for performance-optimized caching)
* **Local Persistence:** Room Database (SQLite abstraction layer)
* **Background Scheduling:** Android Jetpack WorkManager (One-shot chained tasks)
* **Minimum SDK Target:** API 33 (Android 13.0 - Tiramisu) or newer
* **Development Environment:** Android Studio running on Ubuntu Linux with KVM hardware virtualization enabled

---

## 2. Permissions & Manifest Definitions
The application requires explicit system clearance to manipulate device backgrounds and receive boot events.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.customwallpaper">

    <uses-permission android:name="android.permission.SET_WALLPAPER" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

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

        <!-- Broadcast Receiver for boot completion to reschedule evaluation -->
        <receiver
            android:name=".receiver.BootCompletedReceiver"
            android:exported="true"
            android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

### Runtime Broadcast Registration (Android 8.0+ / API 26 Limitation)
Starting with Android 8.0 (API 26), the system enforces restrictions on implicit broadcast receivers declared statically in the `AndroidManifest.xml`. Specifically, broadcast actions like `Intent.ACTION_TIME_CHANGED` (or `android.intent.action.TIME_SET`) and `Intent.ACTION_TIMEZONE_CHANGED` can no longer be declared in the manifest.

Instead, the application must register `TimeChangeReceiver` programmatically at runtime to dynamically listen for these system updates. This is typically done inside the `Application` class lifecycle (during `onCreate`) or within a persistent background service lifecycle:

```kotlin
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.example.customwallpaper.receiver.TimeChangeReceiver

class CustomWallpaperApplication : Application() {
    private lateinit var timeChangeReceiver: TimeChangeReceiver

    override fun onCreate() {
        super.onCreate()

        // Register TimeChangeReceiver programmatically for implicit time broadcasts
        timeChangeReceiver = TimeChangeReceiver()
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(timeChangeReceiver, intentFilter)
    }
}
```


---

## 3. Storage Ingestion Component
To avoid manual cloud provider API integration, the app utilizes the native Storage Access Framework (SAF). This presents a unified system picker displaying internal storage, Google Drive, and OneDrive seamlessly.

⚠️ **Critical Architecture Constraint:** Cloud provider document URIs are ephemeral. Their security flags expire after short periods. To allow the background execution task runner to access files hours later, read permissions must be explicitly persisted to the OS layer.

`ActivityResultContracts.GetContent()` returns ephemeral document URIs that do not support long-term persistable access. Calling `takePersistableUriPermission()` on a URI returned by `GetContent()` will throw a `SecurityException`. To secure a URI that persists across system reboots and background tasks, the app must use `ActivityResultContracts.OpenDocument()`. This contract returns a URI with flags that allow calling `takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`, granting persistent background access without throwing permissions errors.

```kotlin
// Ingestion Framework Setup within an Activity or Composable Context
val pickImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri?.let { secureUri ->
        try {
            // Persist read access across system reboots and long background durations
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(secureUri, takeFlags)

            // Route the verified token to the editing view
            navigateToEditingScreen(secureUri)
        } catch (e: SecurityException) {
            // Handle fallback execution or log error if URI permissions cannot be persisted
            Log.e("StorageIngestion", "Failed to persist URI permission", e)
            navigateToEditingScreen(secureUri)
        }
    }
}

// Triggering the Unified System Picker UI
Button(onClick = { pickImageLauncher.launch(arrayOf("image/*")) }) {
    Text("Select Wallpaper Source")
}
```

---

## 4. UI Layer: Dual-Plane Viewfinder Canvas & Preview Layouts
The UI layer is split into two primary components: the **Schedule Configuration Screen** and the **Crop Editor Screen**.

### Schedule Configuration Screen
The configuration screen contains separate preview thumbnail slots for both the **Home screen** and the **Lock screen**. This allows users to individually preview, select, and customize wallpapers for each screen target. When creating or editing a schedule, tapping a preview slot launches the Storage Access Framework (SAF) picker to load and crop an image for that specific target.

### Crop Editor with Target Selection Dialog
The editing layer leverages a layered `Box` architecture consisting of three stacked planes:
1. **Plane 1 (Bottom):** Coil-rendered raw source image tracking gesture translations (pinch-to-zoom, pan).
2. **Plane 2 (Middle):** Translucent backdrop mask with a centered aspect-ratio viewfinder cut-out stencil.
3. **Plane 3 (Top):** Action headers and confirmation controls.

On tapping **Confirm Selection**, a **Target Selection Dialog/Modal** is presented, letting the user set the crop's destination target: Home screen, Lock screen, or Both.

```kotlin
import android.net.Uri
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

enum class WallpaperTarget {
    HOME, LOCK, BOTH
}

@Composable
fun WallpaperCropEditor(
    imageUri: Uri,
    onCropConfirmed: (scale: Float, offset: Offset, target: WallpaperTarget) -> Unit,
    onCancel: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showTargetDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        // PLANE 1: Interactive Touch Surface (Handles Scaling and Panning Translations)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
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
            val cropWidth = screenWidth * 0.9f
            val cropHeight = screenHeight * 0.8f
            val left = (screenWidth - cropWidth) / 2
            val top = (screenHeight - cropHeight) / 2

            // Draw translucent backing fill
            drawRect(color = Color.Black.copy(alpha = 0.4f))

            // Cut out clear center viewfinder window using Clear blend mode
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(cropWidth, cropHeight),
                blendMode = BlendMode.Clear
            )
        }

        // PLANE 3: Action Controls
        Text(
            text = "⛶ Move and scale image to fit crop area",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp)
        )

        Button(
            onClick = { showTargetDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Text("Confirm Selection")
        }

        // Target Selection Dialog Modal
        if (showTargetDialog) {
            AlertDialog(
                onDismissRequest = { showTargetDialog = false },
                title = { Text("Apply Wallpaper to:") },
                text = { Text("Choose which screen(s) this cropped wallpaper will target.") },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                showTargetDialog = false
                                onCropConfirmed(scale, offset, WallpaperTarget.HOME)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Home Screen Only")
                        }
                        Button(
                            onClick = {
                                showTargetDialog = false
                                onCropConfirmed(scale, offset, WallpaperTarget.LOCK)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Lock Screen Only")
                        }
                        Button(
                            onClick = {
                                showTargetDialog = false
                                onCropConfirmed(scale, offset, WallpaperTarget.BOTH)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Both Screens")
                        }
                        TextButton(
                            onClick = { showTargetDialog = false },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}
```

---

## 5. Media Rendering Pipeline ("Baking")
To decouple background scheduling execution from heavy calculations, image transformation is processed upfront. Reading high-resolution source images directly into memory can trigger an `OutOfMemoryError` (OOM) on mobile devices, especially when parsing large files.

To prevent this, the baking pipeline reads the image's dimensions first without loading its pixels (`inJustDecodeBounds = true`), computes an optimal subsampling factor (`inSampleSize`) based on target display dimensions, loads a downscaled representation into memory, draws it onto an off-screen Canvas matching the display dimensions, and saves the resulting compressed JPEG to internal storage.

```kotlin
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Decodes and subsamples a bitmap from a content Uri to avoid Out-Of-Memory exceptions,
 * then draws it onto an off-screen Canvas scaled to match the physical display dimensions.
 */
fun bakeWallpaperFromUri(
    context: Context,
    sourceUri: Uri,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): File? {
    val contentResolver = context.contentResolver
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels

    try {
        // Step 1: Decode image dimensions only (inJustDecodeBounds = true)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(sourceUri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight

        // Step 2: Calculate the optimal inSampleSize (power of 2)
        var inSampleSize = 1
        if (srcWidth > screenWidth || srcHeight > screenHeight) {
            val halfWidth = srcWidth / 2
            val halfHeight = srcHeight / 2
            while ((halfWidth / inSampleSize) >= screenWidth &&
                   (halfHeight / inSampleSize) >= screenHeight) {
                inSampleSize *= 2
            }
        }

        // Step 3: Decode full bitmap with computed inSampleSize (inJustDecodeBounds = false)
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize

        val subsampledBitmap = contentResolver.openInputStream(sourceUri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        } ?: return null

        // Step 4: Setup off-screen drawing surface matching screen dimensions
        val outputBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Step 5: Adjust transformations mapping subsampled bitmap to the canvas
        // Account for inSampleSize reduction factor in the scaling calculations
        val baseScale = maxOf(screenWidth.toFloat() / srcWidth, screenHeight.toFloat() / srcHeight)
        val adjustedScale = baseScale * scale * inSampleSize
        val matrix = Matrix().apply {
            postScale(adjustedScale, adjustedScale, subsampledBitmap.width / 2f, subsampledBitmap.height / 2f)
            postTranslate(offsetX, offsetY)
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(subsampledBitmap, matrix, paint)
        subsampledBitmap.recycle() // Release intermediate subsampled bitmap from memory

        // Step 6: Compress and persist output file to internal app storage
        val targetFile = File(context.filesDir, "baked_wp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(targetFile).use { outputStream ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        }
        outputBitmap.recycle() // Release final bitmap from memory

        return targetFile
    } catch (e: Exception) {
        Log.e("WallpaperBaker", "Error baking bitmap from Uri: $sourceUri", e)
        return null
    }
}
```

---

## 6. Background Scheduling Engine (Room DB & WorkManager Integration)
The scheduling subsystem leverages Room Database for persistent configuration storage and Jetpack WorkManager for lightweight, boundary-triggered task executions.

### Room Database Schemas
Schedules are stored as rows in a local Room SQLite table. When a user selects "Both" as the screen target, the image is baked once to a single file, and that file's path is stored in both the `home_wallpaper_path` and `lock_wallpaper_path` columns.

```kotlin
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class WallpaperSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "weekdays") val weekdays: String,        // e.g. "MONDAY,TUESDAY,FRIDAY"
    @ColumnInfo(name = "from_time_min") val fromTimeMin: Int,    // Start time (minutes from midnight: 0-1439)
    @ColumnInfo(name = "to_time_min") val toTimeMin: Int,        // End time (minutes from midnight: 0-1439)
    @ColumnInfo(name = "priority") val priority: Int,            // Numerical priority (higher values win)
    @ColumnInfo(name = "home_wallpaper_path") val homeWallpaperPath: String?,
    @ColumnInfo(name = "lock_wallpaper_path") val lockWallpaperPath: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true
)
```

```kotlin
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE is_active = 1")
    suspend fun getActiveSchedules(): List<WallpaperSchedule>

    @Query("SELECT * FROM schedules")
    suspend fun getAllSchedules(): List<WallpaperSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: WallpaperSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: WallpaperSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: WallpaperSchedule)

    @Query("""
        SELECT COUNT(*) FROM schedules
        WHERE home_wallpaper_path = :path OR lock_wallpaper_path = :path
    """)
    suspend fun getPathReferenceCount(path: String): Int
}
```

### Reference-Counted Cleanup Logic (Deletions & Updates)
To prevent dangling files and reclaim local flash memory safely, schedules are cleaned up using a reference-counting mechanism. Before unlinking file paths from disk, the database is queried to determine if other schedules share the same image resources.

⚠️ **Critical Architecture Constraint for Updates:** When a schedule is updated with a new wallpaper image, the application must execute the reference-counted file cleanup routine for the old file path(s) **BEFORE** saving the new path to the database. Failing to do so would result in the database reference count for the old path dropping to 0 after the update, but the app losing track of the old file path, causing an orphaned file storage leak.

```kotlin
import android.content.Context
import java.io.File

/**
 * Safely deletes a schedule and cleans up its associated files if they are not referenced by other schedules.
 */
suspend fun deleteScheduleAndCleanupFiles(
    context: Context,
    dao: ScheduleDao,
    schedule: WallpaperSchedule
) {
    // Delete the schedule row from SQLite database
    dao.deleteSchedule(schedule)

    // Check references for the home wallpaper path
    schedule.homeWallpaperPath?.let { path ->
        val refs = dao.getPathReferenceCount(path)
        if (refs == 0) {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    // Check references for the lock wallpaper path (if different from home)
    schedule.lockWallpaperPath?.let { path ->
        if (path != schedule.homeWallpaperPath) {
            val refs = dao.getPathReferenceCount(path)
            if (refs == 0) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }
}

/**
 * Safely updates a schedule with new wallpaper files, ensuring old files are cleaned up
 * BEFORE the new paths are persisted to prevent storage leaks.
 */
suspend fun updateScheduleAndCleanupFiles(
    context: Context,
    dao: ScheduleDao,
    oldSchedule: WallpaperSchedule,
    newSchedule: WallpaperSchedule
) {
    // 1. Clean up old home wallpaper if changed and no longer referenced
    oldSchedule.homeWallpaperPath?.let { oldPath ->
        if (oldPath != newSchedule.homeWallpaperPath) {
            val refs = dao.getPathReferenceCount(oldPath)
            // If the only reference is the current schedule being updated, refs will be 1
            if (refs <= 1) {
                val file = File(oldPath)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    // 2. Clean up old lock wallpaper if changed and no longer referenced (and not same as home)
    oldSchedule.lockWallpaperPath?.let { oldPath ->
        if (oldPath != newSchedule.lockWallpaperPath && oldPath != oldSchedule.homeWallpaperPath) {
            val refs = dao.getPathReferenceCount(oldPath)
            if (refs <= 1) {
                val file = File(oldPath)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    // 3. Persist the updated schedule to the database
    dao.updateSchedule(newSchedule)
}
```

### Unified Stateful Evaluator
The stateful evaluator resolves the current wallpaper state independently for the Home and Lock screens.
1. It reads the current weekday and the current time in minutes from midnight.
2. It queries active schedules and filters for those containing the current day and matching the current time range (including overnight rules spanning midnight).
3. For each target screen (Home, Lock), rules are sorted independently using a descending order fallback of:
   * **Priority** (Highest wins)
   * **Start Time** (Most recently started wins)
   * **Schedule ID** (Deterministic database primary key tie-breaker)
4. A cache containing the winning `schedule_id` is verified. If the winning ID matches the cache, application is skipped to prevent redundant filesystem read operations. If no scheduled rule is active, a no-op is executed, passively retaining the current wallpaper context.
5. If files are missing, exceptions are caught gracefully to persist current states without crash loops.

```kotlin
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.Calendar

object WallpaperEvaluator {

    private const val PREFS_NAME = "wallpaper_scheduler_prefs"
    private const val KEY_ACTIVE_HOME_ID = "active_home_schedule_id"
    private const val KEY_ACTIVE_LOCK_ID = "active_lock_schedule_id"

    suspend fun evaluateAndApply(context: Context, dao: ScheduleDao) {
        val activeSchedules = dao.getActiveSchedules()

        val calendar = Calendar.getInstance()
        val currentDay = getDayOfWeekString(calendar.get(Calendar.DAY_OF_WEEK))
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentTimeMin = currentHour * 60 + currentMinute

        val yesterdayCalendar = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_WEEK, -1) }
        val yesterday = getDayOfWeekString(yesterdayCalendar.get(Calendar.DAY_OF_WEEK))

        // Filter active rules valid for today and matching current time range (including overnight rules spanning midnight)
        val todaySchedules = activeSchedules.filter { schedule ->
            val days = schedule.weekdays.split(",").map { it.trim().uppercase() }
            if (schedule.fromTimeMin <= schedule.toTimeMin) {
                days.contains(currentDay) && currentTimeMin >= schedule.fromTimeMin && currentTimeMin <= schedule.toTimeMin
            } else {
                (days.contains(currentDay) && currentTimeMin >= schedule.fromTimeMin) ||
                (days.contains(yesterday) && currentTimeMin <= schedule.toTimeMin)
            }
        }

        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedHomeId = sharedPreferences.getLong(KEY_ACTIVE_HOME_ID, -1L)
        val cachedLockId = sharedPreferences.getLong(KEY_ACTIVE_LOCK_ID, -1L)
        val wallpaperManager = WallpaperManager.getInstance(context)

        // Evaluate Home Screen Target
        val homeWinner = todaySchedules
            .filter { it.homeWallpaperPath != null }
            .sortedWith(
                compareByDescending<WallpaperSchedule> { it.priority }
                    .thenByDescending { it.fromTimeMin }
                    .thenByDescending { it.id }
            ).firstOrNull()

        if (homeWinner != null && homeWinner.id != cachedHomeId) {
            applyWallpaperSafe(
                wallpaperManager = wallpaperManager,
                filePath = homeWinner.homeWallpaperPath!!,
                flag = WallpaperManager.FLAG_SYSTEM
            ) {
                sharedPreferences.edit().putLong(KEY_ACTIVE_HOME_ID, homeWinner.id).apply()
            }
        } else if (homeWinner == null) {
            // Unoccupied slot: No-op fallback logic. Allow current wallpaper to persist.
        }

        // Evaluate Lock Screen Target
        val lockWinner = todaySchedules
            .filter { it.lockWallpaperPath != null }
            .sortedWith(
                compareByDescending<WallpaperSchedule> { it.priority }
                    .thenByDescending { it.fromTimeMin }
                    .thenByDescending { it.id }
            ).firstOrNull()

        if (lockWinner != null && lockWinner.id != cachedLockId) {
            applyWallpaperSafe(
                wallpaperManager = wallpaperManager,
                filePath = lockWinner.lockWallpaperPath!!,
                flag = WallpaperManager.FLAG_LOCK
            ) {
                sharedPreferences.edit().putLong(KEY_ACTIVE_LOCK_ID, lockWinner.id).apply()
            }
        } else if (lockWinner == null) {
            // Unoccupied slot: No-op fallback logic. Allow current wallpaper to persist.
        }
    }

    private fun applyWallpaperSafe(
        wallpaperManager: WallpaperManager,
        filePath: String,
        flag: Int,
        onSuccess: () -> Unit
    ) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    wallpaperManager.setBitmap(bitmap, null, true, flag)
                    bitmap.recycle()
                    onSuccess()
                }
            } else {
                Log.w("WallpaperEvaluator", "Wallpaper file missing: $filePath. Passive fallback logic activated.")
            }
        } catch (e: Exception) {
            Log.e("WallpaperEvaluator", "Failed to apply wallpaper for flag: $flag", e)
        }
    }

    private fun getDayOfWeekString(calendarDay: Int): String {
        return when (calendarDay) {
            Calendar.SUNDAY -> "SUNDAY"
            Calendar.MONDAY -> "MONDAY"
            Calendar.TUESDAY -> "TUESDAY"
            Calendar.WEDNESDAY -> "WEDNESDAY"
            Calendar.THURSDAY -> "THURSDAY"
            Calendar.FRIDAY -> "FRIDAY"
            Calendar.SATURDAY -> "SATURDAY"
            else -> ""
        }
    }
}
```

### Chained Boundary Scheduling & WorkManager Task Queue
To transition wallpapers precisely on schedule boundaries without maintaining a power-hungry background service, WorkManager is configured to chain one-shot requests:
1. The scheduler calculates the remaining duration (in milliseconds) to the very next chronological boundary (either a `start` or `end` time) among all active schedule rules.
2. A one-shot `WorkRequest` is queued with the computed delay.
3. Upon execution, the worker runs the evaluation logic, calculates the next transition delay, and queues the next one-shot work request.

```kotlin
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

fun scheduleNextEvaluation(context: Context, activeSchedules: List<WallpaperSchedule>) {
    if (activeSchedules.isEmpty()) {
        WorkManager.getInstance(context).cancelUniqueWork("WallpaperEvaluationWork")
        return
    }

    val calendar = Calendar.getInstance()
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val currentMinute = calendar.get(Calendar.MINUTE)
    val currentSecond = calendar.get(Calendar.SECOND)
    val currentTimeMs = ((currentHour * 60 + currentMinute) * 60 + currentSecond) * 1000L

    var minDelayMs = Long.MAX_VALUE

    for (schedule in activeSchedules) {
        val startMs = schedule.fromTimeMin * 60 * 1000L
        val endMs = schedule.toTimeMin * 60 * 1000L

        for (boundaryMs in listOf(startMs, endMs)) {
            var diff = boundaryMs - currentTimeMs
            if (diff <= 0) {
                // If the boundary time has already passed today, map it to tomorrow
                diff += 24 * 60 * 60 * 1000L
            }
            if (diff < minDelayMs) {
                minDelayMs = diff
            }
        }
    }

    // Coerce delay to a minimum of 15 seconds to prevent rapid rescheduling hot loops
    val finalDelayMs = minDelayMs.coerceAtLeast(15000L)

    val oneShotRequest = OneTimeWorkRequestBuilder<WallpaperEvaluationWorker>()
        .setInitialDelay(finalDelayMs, TimeUnit.MILLISECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "WallpaperEvaluationWork",
        ExistingWorkPolicy.REPLACE,
        oneShotRequest
    )
}
```

```kotlin
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WallpaperEvaluationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = WallpaperDatabase.getInstance(applicationContext)
        val dao = database.scheduleDao()

        return try {
            // Run stateful evaluation engine
            WallpaperEvaluator.evaluateAndApply(applicationContext, dao)

            // Re-calculate boundary transitions and chain next worker
            val activeSchedules = dao.getActiveSchedules()
            scheduleNextEvaluation(applicationContext, activeSchedules)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
```

### Broadcast Receiver Implementations
Receivers respond to device restarts or clock changes (timezone shifts, time corrections) and trigger immediate state evaluations to maintain scheduling synchronization.

```kotlin
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            triggerImmediateEvaluation(context)
        }
    }
}

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            triggerImmediateEvaluation(context)
        }
    }
}

private fun triggerImmediateEvaluation(context: Context) {
    val immediateRequest = OneTimeWorkRequestBuilder<WallpaperEvaluationWorker>()
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "WallpaperEvaluationWork",
        ExistingWorkPolicy.REPLACE,
        immediateRequest
    )
}
```
