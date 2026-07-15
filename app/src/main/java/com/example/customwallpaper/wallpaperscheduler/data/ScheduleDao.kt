package com.example.customwallpaper.wallpaperscheduler.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for the schedules database table.
 */
@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE is_active = 1")
    suspend fun getActiveSchedules(): List<WallpaperSchedule>

    @Query("SELECT * FROM schedules")
    suspend fun getAllSchedules(): List<WallpaperSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: WallpaperSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: WallpaperSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: WallpaperSchedule)

    @Delete
    suspend fun deleteSchedules(schedules: List<WallpaperSchedule>)

    @Query("SELECT COUNT(*) FROM schedules WHERE home_wallpaper_path = :path OR lock_wallpaper_path = :path")
    suspend fun getPathReferenceCount(path: String): Int
}
