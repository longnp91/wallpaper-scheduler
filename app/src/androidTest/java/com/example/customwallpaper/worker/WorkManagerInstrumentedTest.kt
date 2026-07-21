package com.example.customwallpaper.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.customwallpaper.wallpaperscheduler.data.ScheduleDao
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class WorkManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: WallpaperDatabase
    private lateinit var dao: ScheduleDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        database =
            Room.inMemoryDatabaseBuilder(context, WallpaperDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.scheduleDao()

        setDatabaseInstance(database)
    }

    @After
    fun tearDown() {
        setDatabaseInstance(null)
        database.close()
    }

    private fun setDatabaseInstance(db: WallpaperDatabase?) {
        val field = WallpaperDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, db)
    }

    @Test
    fun testScheduler_BoundaryDelayCalculationAndWorkEnqueue() =
        runBlocking {
            // Current local time simulation base
            val now = LocalTime.now()
            val currentTimeMin = now.hour * 60 + now.minute

            // Define a boundary starting 30 minutes from now
            val fromTime = (currentTimeMin + 30) % 1440
            val toTime = (currentTimeMin + 90) % 1440

            val schedule =
                WallpaperSchedule(
                    id = 999L,
                    weekdays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
                    fromTimeMin = fromTime,
                    toTimeMin = toTime,
                    homeWallpaperPath = "/dummy/path/home.jpg",
                    lockWallpaperPath = null,
                    isActive = true,
                )
            dao.insertSchedule(schedule)

            // Trigger next evaluation scheduling
            WallpaperSchedulerHelper.scheduleNextEvaluation(context, listOf(schedule))

            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("WallpaperEvaluationWork").get()

            assertEquals(1, workInfos.size)
            val workInfo = workInfos.first()
            assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)

            // Trigger execution immediately utilizing WorkManager test driver
            val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
            assertNotNull(testDriver)
            testDriver!!.setInitialDelayMet(workInfo.id)

            // Meeting the delay runs the worker asynchronously. On its happy path,
            // doWork() calls scheduleNextEvaluation(...), which re-enqueues this
            // unique work with ExistingWorkPolicy.REPLACE — so the original
            // workInfo.id is deleted and replaced by a new ENQUEUED request. Poll
            // until that re-enqueue is observed: a different work id under the same
            // unique name proves the worker executed through to the rescheduling
            // step (which immediately precedes Result.success()).
            val originalId = workInfo.id
            val deadlineMs = System.currentTimeMillis() + 10_000L
            var reEnqueuedByWorker = false
            while (System.currentTimeMillis() < deadlineMs) {
                val infos = workManager.getWorkInfosForUniqueWork("WallpaperEvaluationWork").get()
                if (infos.any { it.id != originalId && it.state == WorkInfo.State.ENQUEUED }) {
                    reEnqueuedByWorker = true
                    break
                }
                Thread.sleep(100)
            }
            assertTrue(
                "Worker did not re-enqueue the next-boundary work after the delay was met",
                reEnqueuedByWorker,
            )
        }

    @Test
    fun testScheduler_EmptyActiveSchedules_CancelsUniqueWork() =
        runBlocking {
            // 1. Enqueue unique work first to simulate an active schedule state
            val schedule =
                WallpaperSchedule(
                    id = 999L,
                    weekdays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
                    fromTimeMin = 0,
                    toTimeMin = 60,
                    homeWallpaperPath = "/dummy/path/home.jpg",
                    lockWallpaperPath = null,
                    isActive = true,
                )
            WallpaperSchedulerHelper.scheduleNextEvaluation(context, listOf(schedule))

            val workManager = WorkManager.getInstance(context)
            var workInfos = workManager.getWorkInfosForUniqueWork("WallpaperEvaluationWork").get()
            assertEquals(1, workInfos.size)
            assertEquals(WorkInfo.State.ENQUEUED, workInfos.first().state)

            // 2. Schedule with empty schedules (or no active schedules) to trigger cancellation
            WallpaperSchedulerHelper.scheduleNextEvaluation(context, emptyList())

            // 3. Verify that the unique work is cancelled
            val workInfosAfterCancel = workManager.getWorkInfosForUniqueWork("WallpaperEvaluationWork").get()
            assertTrue(
                "Unique work should be cancelled when no schedules are active",
                workInfosAfterCancel.isEmpty() || workInfosAfterCancel.all { it.state == WorkInfo.State.CANCELLED },
            )
        }
}
