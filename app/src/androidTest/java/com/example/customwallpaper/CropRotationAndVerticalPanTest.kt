package com.example.customwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.ImageRequest
import com.example.customwallpaper.ui.screens.WallpaperCropEditorScreen
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class CropRotationAndVerticalPanTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var viewModel: ScheduleViewModel
    private lateinit var testImageFile: File
    private lateinit var testImageUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        viewModel = ScheduleViewModel(database.scheduleDao())

        // 700x400 landscape fixture: RED left (0..400), BLUE right (400..700).
        // EXIF orientation ROTATE_90 means a correct viewer rotates it 90deg CW ->
        // portrait with RED on TOP, BLUE on BOTTOM, filling the screen vertically.
        // Coil's AsyncImage auto-applies EXIF; the production code ALSO applies
        // graphicsLayer(rotationZ), so the buggy preview re-rotates an already-correct
        // bitmap. These tests document that double-rotation bug (expected RED).
        testImageFile = File(context.cacheDir, "crop_rotation_test.jpg")
        if (testImageFile.exists()) testImageFile.delete()
        val bitmap = Bitmap.createBitmap(700, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 400f, 400f, Paint().apply { color = Color.RED })
        canvas.drawRect(400f, 0f, 700f, 400f, Paint().apply { color = Color.BLUE })
        FileOutputStream(testImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        // Tag the JPEG so the correct display orientation is portrait (ROTATE_90).
        val exif = ExifInterface(testImageFile.absolutePath)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_ROTATE_90.toString(),
        )
        exif.saveAttributes()

        testImageUri = Uri.fromFile(testImageFile)

        // Each test must render from a fresh decode so the preview state is
        // deterministic and not polluted by a prior test's cached bitmap.
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()

        // Pre-warm Coil's memory cache so AsyncImage renders synchronously on first
        // composition. The cached bitmap is already EXIF-rotated (portrait); the
        // buggy preview will then re-rotate it via graphicsLayer(rotationZ).
        runBlocking {
            context.imageLoader.execute(
                ImageRequest.Builder(context).data(testImageUri).build(),
            )
        }
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testImageFile.isInitialized && testImageFile.exists()) testImageFile.delete()
        if (::viewModel.isInitialized) viewModel.cleanupSessionTempFiles(excludeSaved = false)
    }

    @Test
    fun verifyExifRotatedImageNotDoubleRotated() {
        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }
        composeTestRule.onNodeWithText("Crop & Confirm").assertExists()

        // Drive the Compose clock until AsyncImage paints (center non-black).
        val renderDeadlineMs = System.currentTimeMillis() + 10_000L
        var imageRendered = false
        while (System.currentTimeMillis() < renderDeadlineMs) {
            composeTestRule.waitForIdle()
            val rendered = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
            val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
            if (!(Color.red(center) == 0 && Color.green(center) == 0 && Color.blue(center) == 0)) {
                imageRendered = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(
            "AsyncImage never painted within timeout (screen center stayed black)",
            imageRendered,
        )

        // Let the AsyncImage + graphicsLayer settle past any transient frame. The
        // preview can emit a brief non-final frame as Coil paints and the
        // LaunchedEffect-driven graphicsLayer recomposes; capturing on the first
        // non-black center is flaky. 800ms settle was validated deterministic
        // (4/4 identical renders) by a throwaway diagnostic.
        composeTestRule.waitForIdle()
        Thread.sleep(800)
        composeTestRule.waitForIdle()

        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val redFrac =
            bandFraction(
                bmp,
                (bmp.width * 0.40).toInt(),
                (bmp.width * 0.60).toInt(),
                (bmp.height * 0.10).toInt(),
                (bmp.height * 0.16).toInt(),
            ) { isRedDominant(it) }
        // Bottom band intentionally sits below the ~57% red/blue split and above
        // the bottom button/chip overlay, so it must stay in roughly [0.6H, 0.75H].
        val blueFrac =
            bandFraction(
                bmp,
                (bmp.width * 0.40).toInt(),
                (bmp.width * 0.60).toInt(),
                (bmp.height * 0.66).toInt(),
                (bmp.height * 0.72).toInt(),
            ) { isBlueDominant(it) }

        assertTrue(
            "BUG DETECTED: EXIF-rotated image is re-rotated by the preview (double rotation). " +
                "Expected RED-dominant at the TOP band; redFrac=$redFrac",
            redFrac > 0.5,
        )
        assertTrue(
            "BUG DETECTED: EXIF-rotated image is re-rotated by the preview (double rotation). " +
                "Expected BLUE-dominant at the BOTTOM band; blueFrac=$blueFrac",
            blueFrac > 0.5,
        )
    }

    @Test
    fun verifyNoVerticalBlackBarsOnRotatedImage() {
        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }
        composeTestRule.onNodeWithText("Crop & Confirm").assertExists()

        // Drive the Compose clock until AsyncImage paints (center non-black).
        val renderDeadlineMs = System.currentTimeMillis() + 10_000L
        var imageRendered = false
        while (System.currentTimeMillis() < renderDeadlineMs) {
            composeTestRule.waitForIdle()
            val rendered = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
            val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
            if (!(Color.red(center) == 0 && Color.green(center) == 0 && Color.blue(center) == 0)) {
                imageRendered = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(
            "AsyncImage never painted within timeout (screen center stayed black)",
            imageRendered,
        )

        // Let the AsyncImage + graphicsLayer settle past any transient frame. The
        // preview can emit a brief non-final frame as Coil paints and the
        // LaunchedEffect-driven graphicsLayer recomposes; capturing on the first
        // non-black center is flaky. 800ms settle was validated deterministic
        // (4/4 identical renders) by a throwaway diagnostic.
        composeTestRule.waitForIdle()
        Thread.sleep(800)
        composeTestRule.waitForIdle()

        val rest = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        assertFalse(
            "BUG DETECTED: black bar at TOP edge at rest (rotated-image coverage gap)",
            hasBlackEdgeRow(rest, checkTopEdge = true),
        )
        assertFalse(
            "BUG DETECTED: black bar at BOTTOM edge at rest (rotated-image coverage gap)",
            hasBlackEdgeRow(rest, checkTopEdge = false),
        )

        // PARTIAL-FIX SENTINEL: after a correct fix vertical pan cannot expose black bars
        // — either because the clamp is zero (if we align baseScale to rotated dims and
        // drop graphicsLayer rotation) or because the image remains taller than the
        // viewport (if we disable Coil EXIF and keep graphicsLayer rotation). The
        // at-rest black-edge checks above are the primary assertion; the post-swipe
        // checks below mainly guard against a fix that drops the double-rotation but
        // leaves a clamp non-zero.
        // --- Pan UP iterated to the vertical clamp ---
        repeat(8) {
            composeTestRule.onRoot().performTouchInput {
                swipe(
                    start = Offset(centerX, height - 10f),
                    end = Offset(centerX, 10f),
                    durationMillis = 80,
                )
            }
            composeTestRule.waitForIdle()
        }

        val afterUp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        assertFalse(
            "BUG DETECTED: black bar at TOP edge after vertical pan-up",
            hasBlackEdgeRow(afterUp, checkTopEdge = true),
        )
        assertFalse(
            "BUG DETECTED: black bar at BOTTOM edge after vertical pan-up",
            hasBlackEdgeRow(afterUp, checkTopEdge = false),
        )

        // --- Pan DOWN iterated to the vertical clamp ---
        repeat(8) {
            composeTestRule.onRoot().performTouchInput {
                swipe(
                    start = Offset(centerX, 10f),
                    end = Offset(centerX, height - 10f),
                    durationMillis = 80,
                )
            }
            composeTestRule.waitForIdle()
        }

        val afterDown = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        assertFalse(
            "BUG DETECTED: black bar at TOP edge after vertical pan-down",
            hasBlackEdgeRow(afterDown, checkTopEdge = true),
        )
        assertFalse(
            "BUG DETECTED: black bar at BOTTOM edge after vertical pan-down",
            hasBlackEdgeRow(afterDown, checkTopEdge = false),
        )
    }

    private fun isRedDominant(color: Int): Boolean = Color.red(color) > 150 && Color.green(color) < 100 && Color.blue(color) < 100

    private fun isBlueDominant(color: Int): Boolean = Color.blue(color) > 150 && Color.red(color) < 100 && Color.green(color) < 100

    private fun bandFraction(
        b: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        pred: (Int) -> Boolean,
    ): Double {
        var matched = 0
        var sampled = 0
        for (x in x0..x1) {
            for (y in y0..y1) {
                sampled++
                if (pred(b.getPixel(x, y))) matched++
            }
        }
        return if (sampled == 0) 0.0 else matched.toDouble() / sampled
    }

    private fun hasBlackEdgeRow(
        bitmap: Bitmap,
        checkTopEdge: Boolean,
    ): Boolean {
        val y = if (checkTopEdge) 5 else bitmap.height - 5
        val left = bitmap.width / 4
        val right = bitmap.width * 3 / 4
        for (x in left..right) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) == 0 && Color.green(c) == 0 && Color.blue(c) == 0) return true
        }
        return false
    }
}
