package com.example.customwallpaper.wallpaperscheduler.engine

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.example.customwallpaper.wallpaperscheduler.data.ScheduleDao
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

object WallpaperEvaluator {
    private const val TAG = "WallpaperEvaluator"
    private const val PREFS_NAME = "wallpaper_scheduler_prefs"
    private const val KEY_ACTIVE_HOME_ID = "active_home_schedule_id"
    private const val KEY_ACTIVE_LOCK_ID = "active_lock_schedule_id"

    suspend fun evaluateAndApply(
        context: Context,
        dao: ScheduleDao,
        wallpaperManager: WallpaperManager = WallpaperManager.getInstance(context),
    ) = withContext(Dispatchers.IO) {
        val localDate = LocalDate.now()
        val currentDay = localDate.dayOfWeek.name
        val yesterday = localDate.minusDays(1).dayOfWeek.name

        val localTime = LocalTime.now()
        val currentTimeMin = localTime.hour * 60 + localTime.minute

        Log.d(TAG, "evaluateAndApply: currentDay=$currentDay, yesterday=$yesterday, currentTimeMin=$currentTimeMin")

        val activeSchedules = dao.getActiveSchedules()

        // Filter rules: a rule matches if the weekday list splits contains today's name AND:
        // - If fromTimeMin <= toTimeMin: currentTimeMin is between them (inclusive).
        // - If fromTimeMin > toTimeMin (overnight): currentTimeMin >= fromTimeMin OR currentTimeMin <= toTimeMin (checking yesterday's weekday).
        val todaySchedules =
            activeSchedules.filter { schedule ->
                val days = schedule.weekdays.split(",").map { it.trim().uppercase() }
                if (schedule.fromTimeMin <= schedule.toTimeMin) {
                    days.contains(currentDay) && currentTimeMin >= schedule.fromTimeMin && currentTimeMin <= schedule.toTimeMin
                } else {
                    (days.contains(currentDay) && currentTimeMin >= schedule.fromTimeMin) ||
                        (days.contains(yesterday) && currentTimeMin <= schedule.toTimeMin)
                }
            }

        // Calculate adjustedStartMin:
        // - If rule is overnight and matched because it started yesterday: adjustedStartMin = fromTimeMin - 1440.
        // - Else: adjustedStartMin = fromTimeMin.
        val matchingWithAdjustedStart =
            todaySchedules.map { schedule ->
                val days = schedule.weekdays.split(",").map { it.trim().uppercase() }
                val isOvernight = schedule.fromTimeMin > schedule.toTimeMin
                val startedYesterday = isOvernight && currentTimeMin <= schedule.toTimeMin && days.contains(yesterday)
                val adjustedStartMin =
                    if (startedYesterday) {
                        schedule.fromTimeMin - 1440
                    } else {
                        schedule.fromTimeMin
                    }
                Pair(schedule, adjustedStartMin)
            }

        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedHomeId = sharedPreferences.getLong(KEY_ACTIVE_HOME_ID, -1L)
        val cachedLockId = sharedPreferences.getLong(KEY_ACTIVE_LOCK_ID, -1L)

        // Resolve the winner schedule for Home and Lock targets independently by sorting:
        // 1. Priority (higher wins)
        // 2. Adjusted Start Time (most recently started wins: higher adjustedStartMin)
        // 3. Schedule ID (descending tie-breaker)
        val homeWinner =
            matchingWithAdjustedStart
                .filter { it.first.homeWallpaperPath != null }
                .sortedWith(
                    compareByDescending<Pair<WallpaperSchedule, Int>> { it.first.priority }
                        .thenByDescending { it.second }
                        .thenByDescending { it.first.id },
                )
                .firstOrNull()?.first

        val lockWinner =
            matchingWithAdjustedStart
                .filter { it.first.lockWallpaperPath != null }
                .sortedWith(
                    compareByDescending<Pair<WallpaperSchedule, Int>> { it.first.priority }
                        .thenByDescending { it.second }
                        .thenByDescending { it.first.id },
                )
                .firstOrNull()?.first

        // Apply Home Wallpaper
        if (homeWinner != null) {
            if (homeWinner.id != cachedHomeId) {
                Log.d(TAG, "Applying Home wallpaper schedule: ID=${homeWinner.id}, path=${homeWinner.homeWallpaperPath}")
                try {
                    val file = File(homeWinner.homeWallpaperPath!!)
                    file.inputStream().use { stream ->
                        wallpaperManager.setStream(stream, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                    sharedPreferences.edit().putLong(KEY_ACTIVE_HOME_ID, homeWinner.id).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying Home wallpaper for schedule ID ${homeWinner.id}", e)
                }
            } else {
                Log.d(TAG, "Home wallpaper schedule matches cached ID=${homeWinner.id}. Bypassing.")
            }
        } else {
            Log.d(TAG, "No winner for Home wallpaper target. Resetting cache.")
            sharedPreferences.edit().putLong(KEY_ACTIVE_HOME_ID, -1L).apply()
        }

        // Apply Lock Wallpaper
        if (lockWinner != null) {
            if (lockWinner.id != cachedLockId) {
                Log.d(TAG, "Applying Lock wallpaper schedule: ID=${lockWinner.id}, path=${lockWinner.lockWallpaperPath}")
                try {
                    val file = File(lockWinner.lockWallpaperPath!!)
                    file.inputStream().use { stream ->
                        wallpaperManager.setStream(stream, null, true, WallpaperManager.FLAG_LOCK)
                    }
                    sharedPreferences.edit().putLong(KEY_ACTIVE_LOCK_ID, lockWinner.id).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying Lock wallpaper for schedule ID ${lockWinner.id}", e)
                }
            } else {
                Log.d(TAG, "Lock wallpaper schedule matches cached ID=${lockWinner.id}. Bypassing.")
            }
        } else {
            Log.d(TAG, "No winner for Lock wallpaper target. Resetting cache.")
            sharedPreferences.edit().putLong(KEY_ACTIVE_LOCK_ID, -1L).apply()
        }
    }
}
