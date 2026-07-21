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
class CropBoundaryBugTest {
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

        // Wide (landscape) fixture. Left part RED, right part BLUE, split off-center
        // so that: offset=0 -> screen center is RED; left-clamp -> BLUE; right-clamp -> RED.
        // Both colors are non-black, so a background leak reads as pure black (0,0,0).
        testImageFile = File(context.cacheDir, "crop_boundary_test.jpg")
        if (testImageFile.exists()) testImageFile.delete()
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 500f, 400f, Paint().apply { color = Color.RED })
        canvas.drawRect(500f, 0f, 800f, 400f, Paint().apply { color = Color.BLUE })
        FileOutputStream(testImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()
        testImageUri = Uri.fromFile(testImageFile)

        // Pre-warm Coil's memory cache so AsyncImage renders synchronously on first
        // composition (deterministic; avoids flaky async-IO render waits).
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
    fun verifyNoBlackBordersVisibleAfterPanning() {
        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = testImageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }
        // Image branch is composed once imageDimensions are decoded.
        composeTestRule.onNodeWithText("Crop & Confirm").assertExists()

        // AsyncImage paints asynchronously even on a memory-cache hit; drive the
        // Compose clock until the screen center is no longer the black background.
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

        // --- Pan hard LEFT (iterated on-screen swipes) to the -maxOffsetX clamp ---
        repeat(8) {
            composeTestRule.onRoot().performTouchInput {
                swipe(
                    start = Offset(width - 10f, centerY),
                    end = Offset(10f, centerY),
                    durationMillis = 80,
                )
            }
            composeTestRule.waitForIdle()
        }

        val afterPanLeft = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val centerLeft = afterPanLeft.getPixel(afterPanLeft.width / 2, afterPanLeft.height / 2)
        // Movement sentinel: a real left-pan shifts the BLUE half to the screen center.
        // If this fails, the pan did not register and the border checks are meaningless.
        assertTrue(
            "Pan-left did not move the image (expected BLUE at center), center=0x" +
                Integer.toHexString(centerLeft),
            isBlueDominant(centerLeft),
        )
        assertFalse(
            "BUG DETECTED: black border on the right edge after panning left",
            hasBlackEdgeColumn(afterPanLeft, checkRightEdge = true),
        )

        // --- Pan hard RIGHT to the +maxOffsetX clamp ---
        repeat(8) {
            composeTestRule.onRoot().performTouchInput {
                swipe(
                    start = Offset(10f, centerY),
                    end = Offset(width - 10f, centerY),
                    durationMillis = 80,
                )
            }
            composeTestRule.waitForIdle()
        }

        val afterPanRight = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val centerRight = afterPanRight.getPixel(afterPanRight.width / 2, afterPanRight.height / 2)
        assertTrue(
            "Pan-right did not move the image (expected RED at center), center=0x" +
                Integer.toHexString(centerRight),
            isRedDominant(centerRight),
        )
        assertFalse(
            "BUG DETECTED: black border on the left edge after panning right",
            hasBlackEdgeColumn(afterPanRight, checkRightEdge = false),
        )
    }

    private fun isRedDominant(color: Int): Boolean = Color.red(color) > 150 && Color.green(color) < 100 && Color.blue(color) < 100

    private fun isBlueDominant(color: Int): Boolean = Color.blue(color) > 150 && Color.red(color) < 100 && Color.green(color) < 100

    private fun hasBlackEdgeColumn(
        bitmap: Bitmap,
        checkRightEdge: Boolean,
    ): Boolean {
        // Scan a column 5px inside the chosen edge, over the vertical middle half
        // (avoids the bottom button overlay).
        val x = if (checkRightEdge) bitmap.width - 5 else 5
        val top = bitmap.height / 4
        val bottom = bitmap.height * 3 / 4
        for (y in top..bottom) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) == 0 && Color.green(c) == 0 && Color.blue(c) == 0) return true
        }
        return false
    }
}
