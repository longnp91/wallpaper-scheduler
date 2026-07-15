package com.example.customwallpaper.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WallpaperSchedulerHelper {
    fun scheduleNextEvaluation(
        context: Context,
        activeSchedules: List<WallpaperSchedule>,
    ) {
        if (activeSchedules.isEmpty()) {
            WorkManager.getInstance(context).cancelUniqueWork("WallpaperEvaluationWork")
            return
        }

        val currentTimeMs = LocalTime.now().toNanoOfDay() / 1_000_000L
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

        val oneShotRequest =
            OneTimeWorkRequestBuilder<WallpaperEvaluationWorker>()
                .setInitialDelay(finalDelayMs, TimeUnit.MILLISECONDS)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "WallpaperEvaluationWork",
            ExistingWorkPolicy.REPLACE,
            oneShotRequest,
        )
    }

    fun triggerImmediateEvaluation(context: Context) {
        val oneShotRequest =
            OneTimeWorkRequestBuilder<WallpaperEvaluationWorker>()
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "WallpaperEvaluationWork",
            ExistingWorkPolicy.REPLACE,
            oneShotRequest,
        )
    }
}
