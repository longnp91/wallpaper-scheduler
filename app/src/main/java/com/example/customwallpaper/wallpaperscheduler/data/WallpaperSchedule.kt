package com.example.customwallpaper.wallpaperscheduler.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a wallpaper schedule rule in the local database.
 */
@Entity(tableName = "schedules")
data class WallpaperSchedule(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "weekdays")
    val weekdays: String,
    @ColumnInfo(name = "from_time_min")
    val fromTimeMin: Int,
    @ColumnInfo(name = "to_time_min")
    val toTimeMin: Int,
    @ColumnInfo(name = "home_wallpaper_path")
    val homeWallpaperPath: String?,
    @ColumnInfo(name = "lock_wallpaper_path")
    val lockWallpaperPath: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
)
