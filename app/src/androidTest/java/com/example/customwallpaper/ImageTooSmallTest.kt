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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageTooSmallTest {
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

        // Create a standard test image (1920 x 1080) - typical photo size
        testImageFile = File(context.cacheDir, "too_small_test.jpg")
        if (testImageFile.exists()) testImageFile.delete()

        val width = 1920
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Create a distinct pattern: left half RED, right half BLUE
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (x < width / 2) {
                    bitmap.setPixel(x, y, Color.RED)
                } else {
                    bitmap.setPixel(x, y, Color.BLUE)
                }
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
    fun verifyImageFillsScreenNotTooSmall() {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }

        // Wait for image to render
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
        assertTrue("Image never rendered", imageRendered)

        // Let rendering settle
        composeTestRule.waitForIdle()
        Thread.sleep(800)
        composeTestRule.waitForIdle()

        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()

        println("Screen size: ${screenWidth}x$screenHeight")
        println("Captured bitmap: ${bmp.width}x${bmp.height}")

        // BUG CHECK: If image is too small, we'll see black background around it
        // Check the edges - if they're black, the image doesn't fill the screen

        // Check top edge (should be RED if image fills screen)
        val topEdge = bmp.getPixel(bmp.width / 2, 20)
        val topRed = Color.red(topEdge)
        val topIsBlack = topRed == 0 && Color.green(topEdge) == 0 && Color.blue(topEdge) == 0

        println("Top edge pixel: R=$topRed G=${Color.green(topEdge)} B=${Color.blue(topEdge)}")
        println("Is top edge black? $topIsBlack")

        // Check left edge (should be RED if image fills screen)
        val leftEdge = bmp.getPixel(20, bmp.height / 2)
        val leftRed = Color.red(leftEdge)
        val leftIsBlack = leftRed == 0 && Color.green(leftEdge) == 0 && Color.blue(leftEdge) == 0

        println("Left edge pixel: R=$leftRed G=${Color.green(leftEdge)} B=${Color.blue(leftEdge)}")
        println("Is left edge black? $leftIsBlack")

        // Check right edge (should be BLUE if image fills screen)
        val rightEdge = bmp.getPixel(bmp.width - 20, bmp.height / 2)
        val rightBlue = Color.blue(rightEdge)
        val rightIsBlack = Color.red(rightEdge) == 0 && Color.green(rightEdge) == 0 && rightBlue == 0

        println("Right edge pixel: R=${Color.red(rightEdge)} G=${Color.green(rightEdge)} B=$rightBlue")
        println("Is right edge black? $rightIsBlack")

        // PRIMARY ASSERTION: Image should fill the screen, not be small with black borders
        // At least one of the edges should NOT be black (image content visible)
        val imageContentVisible = !topIsBlack || !leftIsBlack || !rightIsBlack

        assertTrue(
            "BUG DETECTED: Image appears too small! Edges are black: " +
                "top=$topIsBlack, left=$leftIsBlack, right=$rightIsBlack. " +
                "The image should fill the screen (or overflow for panning), " +
                "not appear as a small rectangle with black background.",
            imageContentVisible,
        )

        // SECONDARY CHECK: Verify we can see both RED and BLUE content
        // (confirms the full image width is visible, not just a tiny portion)
        val centerPixel = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        val centerRed = Color.red(centerPixel)
        val centerBlue = Color.blue(centerPixel)

        println("Center pixel: R=$centerRed B=$centerBlue")

        // Center should be either RED (left half) or BLUE (right half), not black
        val centerHasContent = centerRed > 50 || centerBlue > 50

        assertTrue(
            "BUG DETECTED: Image center is black (R=$centerRed B=$centerBlue). " +
                "Image is too small or not rendering at all.",
            centerHasContent,
        )
    }

    @Test
    fun verifyImageScalesCorrectlyToFillScreen() {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

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

        // Calculate how much of the screen is actually covered by image content
        // (non-black pixels)

        var totalPixels = 0
        var contentPixels = 0

        // Sample a grid of points (every 50 pixels) for efficiency
        for (x in 10 until bmp.width step 50) {
            for (y in 10 until bmp.height step 50) {
                totalPixels++
                val pixel = bmp.getPixel(x, y)
                if (!(Color.red(pixel) == 0 && Color.green(pixel) == 0 && Color.blue(pixel) == 0)) {
                    contentPixels++
                }
            }
        }

        val coverageRatio = if (totalPixels > 0) contentPixels.toDouble() / totalPixels else 0.0

        println("Screen coverage: ${(coverageRatio * 100).toInt()}% ($contentPixels/$totalPixels sample pixels)")

        // ASSERTION: Image should cover most of the screen (at least 80%)
        // A properly scaled image should fill the screen or overflow slightly
        assertTrue(
            "BUG DETECTED: Image only covers ${(coverageRatio * 100).toInt()}% of screen! " +
                "Image appears too small/zoomed out. Expected at least 80% coverage.",
            coverageRatio >= 0.8,
        )
    }
}
