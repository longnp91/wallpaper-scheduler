package com.example.customwallpaper.wallpaperscheduler.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DatabaseCleanupTest {
    private lateinit var db: WallpaperDatabase
    private lateinit var dao: ScheduleDao
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.scheduleDao()
        tempDir = context.filesDir
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createDummyFile(name: String): File {
        val file = File(tempDir, name)
        file.writeText("dummy content")
        return file
    }

    @Test
    fun testIsolatedDeletion_Task5_2() =
        runTest {
            val file = createDummyFile("isolated.jpg")
            val path = file.absolutePath

            val schedule =
                WallpaperSchedule(
                    id = 1L,
                    weekdays = "MONDAY",
                    fromTimeMin = 60,
                    toTimeMin = 120,
                    homeWallpaperPath = path,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            dao.insertSchedule(schedule)

            // Verify reference count initially
            assertEquals(1, dao.getPathReferenceCount(path))
            assertTrue(file.exists())

            // Call deletion and cleanup
            deleteScheduleAndCleanupFiles(context, dao, schedule)

            // Verify ref count and file deletion
            assertEquals(0, dao.getPathReferenceCount(path))
            assertFalse(file.exists())
        }

    @Test
    fun testSharedDeletion_Task5_3() =
        runTest {
            val file = createDummyFile("shared.jpg")
            val path = file.absolutePath

            val scheduleA =
                WallpaperSchedule(
                    id = 1L,
                    weekdays = "MONDAY",
                    fromTimeMin = 60,
                    toTimeMin = 120,
                    homeWallpaperPath = path,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            val scheduleB =
                WallpaperSchedule(
                    id = 2L,
                    weekdays = "TUESDAY",
                    fromTimeMin = 120,
                    toTimeMin = 180,
                    homeWallpaperPath = null,
                    lockWallpaperPath = path,
                    isActive = true,
                )
            dao.insertSchedule(scheduleA)
            dao.insertSchedule(scheduleB)

            // Verify references
            assertEquals(2, dao.getPathReferenceCount(path))
            assertTrue(file.exists())

            // Delete Schedule A
            deleteScheduleAndCleanupFiles(context, dao, scheduleA)
            assertEquals(1, dao.getPathReferenceCount(path))
            assertTrue(file.exists())

            // Delete Schedule B
            deleteScheduleAndCleanupFiles(context, dao, scheduleB)
            assertEquals(0, dao.getPathReferenceCount(path))
            assertFalse(file.exists())
        }

    @Test
    fun testUpdateCleanup_Task5_4() =
        runTest {
            val file1 = createDummyFile("path1.jpg")
            val file2 = createDummyFile("path2.jpg")
            val path1 = file1.absolutePath
            val path2 = file2.absolutePath

            val oldSchedule =
                WallpaperSchedule(
                    id = 1L,
                    weekdays = "MONDAY",
                    fromTimeMin = 60,
                    toTimeMin = 120,
                    homeWallpaperPath = path1,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            dao.insertSchedule(oldSchedule)

            // Update target
            val newSchedule = oldSchedule.copy(homeWallpaperPath = path2)

            // Call update cleanup
            updateScheduleAndCleanupFiles(context, dao, oldSchedule, newSchedule)

            // Verify path1 is deleted, path2 remains
            assertEquals(0, dao.getPathReferenceCount(path1))
            assertEquals(1, dao.getPathReferenceCount(path2))
            assertFalse(file1.exists())
            assertTrue(file2.exists())
        }

    @Test
    fun testBothTarget_Task5_5() =
        runTest {
            val file = createDummyFile("both.jpg")
            val path = file.absolutePath

            // Targeting BOTH Home and Lock screen with the same file
            val schedule =
                WallpaperSchedule(
                    id = 1L,
                    weekdays = "MONDAY",
                    fromTimeMin = 60,
                    toTimeMin = 120,
                    homeWallpaperPath = path,
                    lockWallpaperPath = path,
                    isActive = true,
                )
            dao.insertSchedule(schedule)

            // Verify references (getPathReferenceCount counts matching schedules, so it returns 1 for a single schedule)
            assertEquals(1, dao.getPathReferenceCount(path))
            assertTrue(file.exists())

            // Delete schedule -> should clean up without crashing on duplicate deletion
            deleteScheduleAndCleanupFiles(context, dao, schedule)

            assertEquals(0, dao.getPathReferenceCount(path))
            assertFalse(file.exists())
        }

    @Test
    fun testBatchDeletion_Task5_6() =
        runTest {
            val file1 = createDummyFile("batch1.jpg")
            val file2 = createDummyFile("batch2.jpg")
            val file3 = createDummyFile("batch3.jpg")
            val path1 = file1.absolutePath
            val path2 = file2.absolutePath
            val path3 = file3.absolutePath

            val scheduleA =
                WallpaperSchedule(
                    id = 1L,
                    weekdays = "MONDAY",
                    fromTimeMin = 60,
                    toTimeMin = 120,
                    homeWallpaperPath = path1,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            val scheduleB =
                WallpaperSchedule(
                    id = 2L,
                    weekdays = "TUESDAY",
                    fromTimeMin = 120,
                    toTimeMin = 180,
                    homeWallpaperPath = path1,
                    lockWallpaperPath = path2,
                    isActive = true,
                )
            val scheduleC =
                WallpaperSchedule(
                    id = 3L,
                    weekdays = "WEDNESDAY",
                    fromTimeMin = 180,
                    toTimeMin = 240,
                    homeWallpaperPath = path2,
                    lockWallpaperPath = path3,
                    isActive = true,
                )
            dao.insertSchedule(scheduleA)
            dao.insertSchedule(scheduleB)
            dao.insertSchedule(scheduleC)

            // Run batch delete on A and B
            batchDeleteSchedulesAndCleanupFiles(context, dao, listOf(scheduleA, scheduleB))

            // Verify path1 is deleted (references drop from A and B)
            assertEquals(0, dao.getPathReferenceCount(path1))
            assertFalse(file1.exists())

            // Verify path2 remains (referenced by C)
            assertEquals(1, dao.getPathReferenceCount(path2))
            assertTrue(file2.exists())

            // Verify path3 remains (referenced by C)
            assertEquals(1, dao.getPathReferenceCount(path3))
            assertTrue(file3.exists())
        }
}
