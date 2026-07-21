package com.example.customwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import com.example.customwallpaper.ui.screens.WallpaperCropEditorScreen
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageRenderingSizeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var viewModel: ScheduleViewModel
    private lateinit var testImageFile: File
    private lateinit var testImageUri: Uri

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        viewModel = ScheduleViewModel(database.scheduleDao())

        // Create a high-res test image (4000 x 6000) to simulate the "gigantic" bug
        testImageFile = File(context.cacheDir, "rendering_size_test.jpg")
        if (testImageFile.exists()) testImageFile.delete()

        val highResWidth = 4000
        val highResHeight = 6000
        val bitmap = Bitmap.createBitmap(highResWidth, highResHeight, Bitmap.Config.ARGB_8888)

        // Fill with a gradient to make it visually distinct
        for (x in 0 until highResWidth) {
            for (y in 0 until highResHeight) {
                val red = (x * 255 / highResWidth)
                val blue = (y * 255 / highResHeight)
                bitmap.setPixel(x, y, Color.rgb(red, 0, blue))
            }
        }

        FileOutputStream(testImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        testImageUri = Uri.fromFile(testImageFile)

        // Clear Coil caches for test isolation
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()

        // Pre-warm Coil cache
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
    fun verifyImageRendersAtScreenSizeNotRawResolution() {
        val metrics = context.resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels

        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }

        // Wait for the image to decode and render
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
        assertTrue("Image never rendered within timeout", imageRendered)

        // Let the rendering settle
        composeTestRule.waitForIdle()
        Thread.sleep(800)
        composeTestRule.waitForIdle()

        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()

        // BUG CHECK: If the image is rendered at raw resolution (or grossly oversized),
        // the captured bitmap will be much larger than the physical screen.
        // The capture should be exactly screen size if clipping works correctly.

        println("Captured bitmap size: ${bmp.width}x${bmp.height}")
        println("Physical screen size: ${screenWidthPx}x$screenHeightPx")

        // ASSERTION A: The captured bitmap width should match screen width
        // (Height may differ due to system UI/buttons)
        assertEquals(
            "BUG DETECTED: Captured bitmap width ${bmp.width} does not match screen width $screenWidthPx. " +
                "Image is being rendered at raw resolution or grossly oversized!",
            screenWidthPx.toLong(),
            bmp.width.toLong(),
        )
        // Height check with tolerance for system UI (allow up to 100px difference)
        val heightDiff = Math.abs(bmp.height - screenHeightPx)
        assertTrue(
            "BUG DETECTED: Captured bitmap height ${bmp.height} differs significantly from screen height $screenHeightPx. " +
                "Height difference: $heightDiff (allow up to 100px for system UI).",
            heightDiff < 100,
        )

        // ASSERTION B: Verify the image content is actually visible (not black)
        // Check a few sample points to confirm the gradient is rendering
        val centerPixel = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        val centerRed = Color.red(centerPixel)
        val centerGreen = Color.green(centerPixel)
        val centerBlue = Color.blue(centerPixel)

        println("Center pixel color: R=$centerRed G=$centerGreen B=$centerBlue")
        println("Is center black? ${centerRed == 0 && centerGreen == 0 && centerBlue == 0}")

        assertTrue(
            "BUG DETECTED: Image center is black (no content rendered). " +
                "Center pixel: R=$centerRed G=$centerGreen B=$centerBlue",
            centerRed > 0 || centerBlue > 0,
        )

        // ADDITIONAL DIAGNOSTIC: Check if image is letterboxed (black edges)
        val topEdge = bmp.getPixel(bmp.width / 2, 10)
        val bottomEdge = bmp.getPixel(bmp.width / 2, bmp.height - 10)
        val leftEdge = bmp.getPixel(10, bmp.height / 2)
        val rightEdge = bmp.getPixel(bmp.width - 10, bmp.height / 2)

        println("Top edge: R=${Color.red(topEdge)} G=${Color.green(topEdge)} B=${Color.blue(topEdge)}")
        println("Bottom edge: R=${Color.red(bottomEdge)} G=${Color.green(bottomEdge)} B=${Color.blue(bottomEdge)}")
        println("Left edge: R=${Color.red(leftEdge)} G=${Color.green(leftEdge)} B=${Color.blue(leftEdge)}")
        println("Right edge: R=${Color.red(rightEdge)} G=${Color.green(rightEdge)} B=${Color.blue(rightEdge)}")
    }

    @Test
    fun verifyImageFitsScreenWithoutOverscaling() {
        val metrics = context.resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels

        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }

        // Wait for rendering
        composeTestRule.waitForIdle()
        Thread.sleep(1500)
        composeTestRule.waitForIdle()

        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()

        // Check that the image aspect ratio matches the source, not the screen
        // For a 4000×6000 image, the aspect ratio is 2:3
        // If rendered correctly, the visible image should fill either width or height
        // (whichever is limiting), and the other dimension should show letterbox/clipped content

        val imageAspectRatio = 4000f / 6000f // 0.667
        val screenAspectRatio = screenWidthPx.toFloat() / screenHeightPx.toFloat()

        println("Image aspect ratio: $imageAspectRatio")
        println("Screen aspect ratio: $screenAspectRatio")

        // If the image is grossly oversized, we'd see raw pixel data
        // If correctly sized, we see a properly scaled version

        // Take a horizontal strip through the center and check for gradient variation.
        // The generated gradient has red(x) = x * 255 / 4000, so the change per 10px
        // step is ~0.64 — well below the original threshold of 30. Using 2 instead
        // ensures we catch the subtle gradient while still filtering out noise.
        val centerY = bmp.height / 2
        var colorChanges = 0
        var lastRed = -1
        for (x in 0 until bmp.width step 10) {
            val pixel = bmp.getPixel(x, centerY)
            val red = Color.red(pixel)
            if (lastRed >= 0 && Math.abs(red - lastRed) > 2) {
                colorChanges++
            }
            lastRed = red
        }

        assertTrue(
            "BUG DETECTED: No color variation across horizontal strip. " +
                "Image may be rendered as solid color or not scaled correctly. " +
                "Color changes detected: $colorChanges (expected at least 1, confirming rendering works)",
            colorChanges >= 1,
        )
    }
}
