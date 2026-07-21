package com.example.customwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.customwallpaper.ui.screens.WallpaperCropEditorScreen
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class WallpaperCropUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var viewModel: ScheduleViewModel
    private lateinit var dummyImageFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        viewModel = ScheduleViewModel(database.scheduleDao())

        // Create a small dummy JPEG image on the device's internal storage/cache
        dummyImageFile = File(context.cacheDir, "dummy_crop_test_image.jpg")
        if (dummyImageFile.exists()) {
            dummyImageFile.delete()
        }
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        FileOutputStream(dummyImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
        if (::dummyImageFile.isInitialized && dummyImageFile.exists()) {
            dummyImageFile.delete()
        }
        if (::viewModel.isInitialized) {
            viewModel.cleanupSessionTempFiles(excludeSaved = false)
        }
    }

    @Test
    fun verifyBakedBitmapMatchesScreenDimensions() {
        val imageUri = Uri.fromFile(dummyImageFile)

        // Render WallpaperCropEditorScreen
        composeTestRule.setContent {
            WallpaperCropEditorScreen(
                viewModel = viewModel,
                imageUri = imageUri,
                targetSlot = "home",
                onNavigateBack = {},
            )
        }

        // Programmatically perform clicks on "Crop & Confirm" and "Apply to Home Screen"
        composeTestRule.onNodeWithText("Crop & Confirm").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Apply to Home Screen").performClick()

        // Wait for the baking operation to finish
        var bakedPath: String? = null
        val timeoutMs = 8000L
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val path = viewModel.homeWallpaperPathState.value
            if (path != null && File(path).exists()) {
                bakedPath = path
                break
            }
            Thread.sleep(100)
        }

        assertNotNull("Baking operation did not complete within timeout", bakedPath)
        val bakedFile = File(bakedPath!!)
        assertTrue("Baked file does not exist", bakedFile.exists())

        // Assert that the baked JPEG bitmap matches the device display metrics width and height
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeFile(bakedFile.absolutePath, options)

        val metrics = context.resources.displayMetrics
        val expectedWidth = metrics.widthPixels
        val expectedHeight = metrics.heightPixels

        assertEquals("Baked image width does not match screen width", expectedWidth, options.outWidth)
        assertEquals("Baked image height does not match screen height", expectedHeight, options.outHeight)
    }
}
