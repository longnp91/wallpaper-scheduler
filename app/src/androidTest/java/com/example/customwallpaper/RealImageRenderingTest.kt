package com.example.customwallpaper

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.customwallpaper.ui.screens.WallpaperCropEditorScreen
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class RealImageRenderingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var viewModel: ScheduleViewModel
    private lateinit var testImageUri: Uri

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        viewModel = ScheduleViewModel(database.scheduleDao())

        // Copy the real EXIF-rotated test image from /sdcard/ to the app's private cache
        // directory so ContentResolver.openInputStream() can read it without needing
        // READ_EXTERNAL_STORAGE (which is not granted to the test APK on Android 11+).
        val sourceFile = File("/sdcard/IMG_20230106_211053_753.jpg")
        assertTrue(
            "Test image file must exist on device. Run: adb push samples/IMG_20230106_211053_753.jpg /sdcard/",
            sourceFile.exists(),
        )

        // Copy the test image from the test APK's assets (bundled during build) to the
        // app's cache directory, so the composable (running in the app process) can read it.
        val cachedFile = File(context.cacheDir, "IMG_20230106_211053_753.jpg")
        if (!cachedFile.exists()) {
            val testContext = InstrumentationRegistry.getInstrumentation().context
            testContext.assets.open("IMG_20230106_211053_753.jpg").use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            }
            assertTrue(
                "Failed to cache test image at ${cachedFile.absolutePath}",
                cachedFile.exists() && cachedFile.length() > 1000,
            )
        }
        testImageUri = Uri.fromFile(cachedFile)

        println("Testing with real image: ${sourceFile.absolutePath} (cached to ${cachedFile.absolutePath})")
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::viewModel.isInitialized) viewModel.cleanupSessionTempFiles(excludeSaved = false)
        File(context.cacheDir, "IMG_20230106_211053_753.jpg").delete()
    }

    @Test
    fun verifyRealImageDisplaysAtCorrectSize() {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        println("Screen size: ${screenWidth}x$screenHeight")

        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }

        // Wait for image to render
        val renderDeadlineMs = System.currentTimeMillis() + 15_000L
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

        // Let rendering settle
        composeTestRule.waitForIdle()
        Thread.sleep(1000)
        composeTestRule.waitForIdle()

        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()

        println("Captured bitmap: ${bmp.width}x${bmp.height}")

        // PRIMARY ASSERTION: Image should fill the screen properly
        // Check that the captured bitmap matches screen size (within tolerance for system UI)
        val widthDiff = Math.abs(bmp.width - screenWidth)
        val heightDiff = Math.abs(bmp.height - screenHeight)

        println("Width difference: $widthDiff px")
        println("Height difference: $heightDiff px")

        // Allow some tolerance for system UI (status bar, navigation)
        assertTrue(
            "BUG DETECTED: Captured width ${bmp.width} differs significantly from screen width $screenWidth. " +
                "Image may be displaying at incorrect scale.",
            widthDiff < 100,
        )
        assertTrue(
            "BUG DETECTED: Captured height ${bmp.height} differs significantly from screen height $screenHeight. " +
                "Image may be displaying at incorrect scale.",
            heightDiff < 150,
        )

        // SECONDARY ASSERTION: Verify image content is visible (not black background)
        // Sample multiple points to ensure image fills the screen
        var contentPixels = 0
        var blackPixels = 0
        val samplePoints: List<Pair<Int, Int>> =
            listOf(
                // Center
                Pair(bmp.width / 2, bmp.height / 2),
                // Left quadrant
                Pair(bmp.width / 4, bmp.height / 2),
                // Right quadrant
                Pair(bmp.width * 3 / 4, bmp.height / 2),
                // Top quadrant
                Pair(bmp.width / 2, bmp.height / 4),
                // Bottom quadrant
                Pair(bmp.width / 2, bmp.height * 3 / 4),
            )

        for ((x, y) in samplePoints) {
            val pixel = bmp.getPixel(x, y)
            if (Color.red(pixel) == 0 && Color.green(pixel) == 0 && Color.blue(pixel) == 0) {
                blackPixels++
            } else {
                contentPixels++
            }
        }

        println("Content pixels: $contentPixels, Black pixels: $blackPixels (sample points)")

        assertTrue(
            "BUG DETECTED: Most sample points are black ($blackPixels/$samplePoints.size). " +
                "Image may be too small or not rendering properly.",
            contentPixels >= blackPixels,
        )
    }

    @Test
    fun verifyRealImageNotGigantic() {
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
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // BUG CHECK: If image is gigantic/zoomed in, the captured bitmap
        // will be MUCH larger than the screen size (rendering at raw resolution)

        println("Screen: ${screenWidth}x$screenHeight")
        println("Captured: ${bmp.width}x${bmp.height}")

        // The captured bitmap should be approximately screen size, not raw 4608×2592
        // Allow some tolerance for system UI
        assertTrue(
            "BUG DETECTED: Captured bitmap width ${bmp.width} is much larger than screen width $screenWidth. " +
                "Image is displaying at raw resolution (gigantic/zoomed in)!",
            bmp.width < screenWidth + 200,
        )
        assertTrue(
            "BUG DETECTED: Captured bitmap height ${bmp.height} is much larger than screen height $screenHeight. " +
                "Image is displaying at raw resolution (gigantic/zoomed in)!",
            bmp.height < screenHeight + 200,
        )

        // ADDITIONAL CHECK: Verify we're not seeing just a tiny portion of the image
        // by checking that multiple distinct areas have content
        val centerPixel = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        val topLeftPixel = bmp.getPixel(bmp.width / 4, bmp.height / 4)
        val bottomRightPixel = bmp.getPixel(bmp.width * 3 / 4, bmp.height * 3 / 4)

        val centerHasContent = Color.red(centerPixel) > 0 || Color.green(centerPixel) > 0 || Color.blue(centerPixel) > 0
        val topLeftHasContent = Color.red(topLeftPixel) > 0 || Color.green(topLeftPixel) > 0 || Color.blue(topLeftPixel) > 0
        val bottomRightHasContent = Color.red(bottomRightPixel) > 0 || Color.green(bottomRightPixel) > 0 || Color.blue(bottomRightPixel) > 0

        val areasWithContent = listOf(centerHasContent, topLeftHasContent, bottomRightHasContent).count { it }

        println("Areas with content: $areasWithContent/3")

        assertTrue(
            "BUG DETECTED: Image appears too small/zoomed out. " +
                "Only $areasWithContent/3 sampled areas have visible content.",
            areasWithContent >= 2,
        )
    }
}
