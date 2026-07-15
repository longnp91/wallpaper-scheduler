package com.example.customwallpaper.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import com.example.customwallpaper.wallpaperscheduler.engine.WallpaperEvaluator
import kotlinx.coroutines.CancellationException

class WallpaperEvaluationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = WallpaperDatabase.getInstance(applicationContext)
            val dao = database.scheduleDao()

            // Run evaluate and apply logic
            WallpaperEvaluator.evaluateAndApply(applicationContext, dao)

            // Schedule the next evaluation boundary
            val activeSchedules = dao.getActiveSchedules()
            WallpaperSchedulerHelper.scheduleNextEvaluation(applicationContext, activeSchedules)

            Result.success()
        } catch (e: CancellationException) {
            // Rethrow CancellationException so that WorkManager knows the worker was cancelled
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during wallpaper evaluation execution", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WallpaperEvalWorker"
    }
}
