package com.example.customwallpaper.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.customwallpaper.wallpaperscheduler.data.ScheduleDao
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WallpaperEvaluationWorkerTest {
    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var dao: ScheduleDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 1. Initialize WorkManager in test mode
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        // 2. Initialize an in-memory database to prevent test run state leakage
        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.scheduleDao()

        // Inject the in-memory database into the WallpaperDatabase singleton instance helper
        setDatabaseInstance(database)
    }

    @After
    fun tearDown() {
        setDatabaseInstance(null)
        database.close()
    }

    private fun setDatabaseInstance(db: WallpaperDatabase?) {
        try {
            val field = WallpaperDatabase::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            field.set(null, db)
        } catch (e: Exception) {
            val field = WallpaperDatabase.Companion::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            field.set(WallpaperDatabase.Companion, db)
        }
    }

    @Test
    fun testEvaluationWorker_RunsSuccessfully_Task5_7() =
        runTest {
            // Setup mock schedule inside in-memory database
            val mockFile =
                File(context.filesDir, "mock_wallpaper.jpg").apply {
                    writeText("dummy-data-to-simulate-baked-file")
                }

            val schedule =
                WallpaperSchedule(
                    id = 123L,
                    weekdays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
                    // 12:00 AM
                    fromTimeMin = 0,
                    // 11:59 PM (covers current time)
                    toTimeMin = 1439,
                    homeWallpaperPath = mockFile.absolutePath,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            dao.insertSchedule(schedule)

            // Build worker instance
            val worker = TestListenableWorkerBuilder<WallpaperEvaluationWorker>(context).build()

            // Run worker synchronously in Coroutine Scope
            val result = worker.doWork()

            // Verify that execution completes successfully
            assertEquals(ListenableWorker.Result.success(), result)

            // Cleanup mock file
            if (mockFile.exists()) {
                mockFile.delete()
            }
        }

    @Test
    fun testEvaluationWorker_SideEffects_SharedPreferencesAndNextScheduling() =
        runTest {
            // Setup mock schedule inside in-memory database
            val mockFile =
                File(context.filesDir, "mock_wallpaper_side_effects.jpg").apply {
                    writeText("dummy-data-for-side-effects")
                }

            val schedule =
                WallpaperSchedule(
                    id = 456L,
                    weekdays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
                    fromTimeMin = 0,
                    toTimeMin = 1439,
                    homeWallpaperPath = mockFile.absolutePath,
                    lockWallpaperPath = null,
                    isActive = true,
                )
            dao.insertSchedule(schedule)

            // Build worker instance and run doWork
            val worker = TestListenableWorkerBuilder<WallpaperEvaluationWorker>(context).build()
            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            // 1. Assert SharedPreferences are correctly updated
            val sharedPrefs = context.getSharedPreferences("wallpaper_scheduler_prefs", Context.MODE_PRIVATE)
            assertEquals(456L, sharedPrefs.getLong("active_home_schedule_id", -2L))
            assertEquals(-1L, sharedPrefs.getLong("active_lock_schedule_id", -2L))

            // 2. Verify next evaluation is enqueued with positive delay via TestDriver
            val workManager = androidx.work.WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("WallpaperEvaluationWork").get()
            assertEquals(1, workInfos.size)
            val workInfo = workInfos.first()
            assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)

            // Verify that we can trigger the initial delay using TestDriver
            val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            assertNotNull(testDriver)
            testDriver!!.setInitialDelayMet(workInfo.id)

            // Cleanup mock file
            if (mockFile.exists()) {
                mockFile.delete()
            }
        }

    @Test
    fun testEvaluationWorker_TransientException_ReturnsRetry() =
        runTest {
            // Inject fake database that throws IOException on scheduleDao call
            setDatabaseInstance(FakeExceptionDatabase())

            // Build worker instance
            val worker = TestListenableWorkerBuilder<WallpaperEvaluationWorker>(context).build()

            // Run worker synchronously in Coroutine Scope
            val result = worker.doWork()

            // Verify that execution catches error and returns retry
            assertEquals(ListenableWorker.Result.retry(), result)
        }

    private class FakeExceptionDatabase : WallpaperDatabase() {
        override fun scheduleDao(): ScheduleDao {
            throw java.io.IOException("Simulated transient database exception")
        }

        override fun createInvalidationTracker(): androidx.room.InvalidationTracker {
            return androidx.room.InvalidationTracker(this, "schedules")
        }

        override fun clearAllTables() {
            throw UnsupportedOperationException()
        }

        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            throw UnsupportedOperationException()
        }
    }
}
