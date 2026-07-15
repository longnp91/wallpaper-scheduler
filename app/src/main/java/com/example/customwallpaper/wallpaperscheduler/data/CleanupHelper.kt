package com.example.customwallpaper.wallpaperscheduler.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CleanupHelper"

/**
 * Safely deletes a schedule from the database and cleans up its associated files
 * from the filesystem if they are no longer referenced by any other schedule.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun deleteScheduleAndCleanupFiles(
    context: Context,
    dao: ScheduleDao,
    schedule: WallpaperSchedule,
) {
    Log.d(TAG, "deleteScheduleAndCleanupFiles: ID=${schedule.id}")
    try {
        // Delete the schedule row from SQLite database
        dao.deleteSchedule(schedule)
        Log.d("WallpaperDB", "Delete: ID=${schedule.id}")

        // Check references for the home wallpaper path
        schedule.homeWallpaperPath?.let { path ->
            val refs = dao.getPathReferenceCount(path)
            Log.d("WallpaperStorage", "Reference check for path $path: count = $refs")
            if (refs == 0) {
                safeDeleteFile(path)
            } else {
                Log.d("WallpaperStorage", "File retained: $path (Reason: Ref count is $refs)")
            }
        }

        // Check references for the lock wallpaper path (if different from home)
        schedule.lockWallpaperPath?.let { path ->
            if (path != schedule.homeWallpaperPath) {
                val refs = dao.getPathReferenceCount(path)
                Log.d("WallpaperStorage", "Reference check for path $path: count = $refs")
                if (refs == 0) {
                    safeDeleteFile(path)
                } else {
                    Log.d("WallpaperStorage", "File retained: $path (Reason: Ref count is $refs)")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed deleteScheduleAndCleanupFiles for schedule ID: ${schedule.id}", e)
        throw e
    }
}

/**
 * Safely updates a schedule in the database and cleans up any old files that are no
 * longer referenced by any schedule. Enforces Write-Before-Delete sequence.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun updateScheduleAndCleanupFiles(
    context: Context,
    dao: ScheduleDao,
    oldSchedule: WallpaperSchedule,
    newSchedule: WallpaperSchedule,
) {
    Log.d(TAG, "updateScheduleAndCleanupFiles: ID=${oldSchedule.id}")
    try {
        // 1. Write: Persist the updated schedule to the database first
        dao.updateSchedule(newSchedule)
        val updateMsg =
            buildString {
                append("Update: ID=${newSchedule.id}, ")
                append("oldHome=${oldSchedule.homeWallpaperPath}, ")
                append("newHome=${newSchedule.homeWallpaperPath}, ")
                append("oldLock=${oldSchedule.lockWallpaperPath}, ")
                append("newLock=${newSchedule.lockWallpaperPath}")
            }
        Log.d("WallpaperDB", updateMsg)

        // 2. Delete: Check references of the old wallpaper paths.
        // If they drop to 0, unlink the files.
        oldSchedule.homeWallpaperPath?.let { oldHomePath ->
            val refs = dao.getPathReferenceCount(oldHomePath)
            Log.d("WallpaperStorage", "Reference check for old home path $oldHomePath: count = $refs")
            if (refs == 0) {
                safeDeleteFile(oldHomePath)
            } else {
                Log.d("WallpaperStorage", "File retained: $oldHomePath (Reason: Ref count is $refs)")
            }
        }

        oldSchedule.lockWallpaperPath?.let { oldLockPath ->
            if (oldLockPath != oldSchedule.homeWallpaperPath) {
                val refs = dao.getPathReferenceCount(oldLockPath)
                Log.d("WallpaperStorage", "Reference check for old lock path $oldLockPath: count = $refs")
                if (refs == 0) {
                    safeDeleteFile(oldLockPath)
                } else {
                    Log.d("WallpaperStorage", "File retained: $oldLockPath (Reason: Ref count is $refs)")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed updateScheduleAndCleanupFiles for schedule ID: ${oldSchedule.id}", e)
        throw e
    }
}

/**
 * Performs a safe, batch deletion of multiple schedules, deleting records first in a single transaction,
 * and then cleaning up the associated files if their database reference counts drop to 0.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun batchDeleteSchedulesAndCleanupFiles(
    context: Context,
    dao: ScheduleDao,
    schedulesList: List<WallpaperSchedule>,
) {
    Log.d(TAG, "batchDeleteSchedulesAndCleanupFiles: size=${schedulesList.size}")
    try {
        // 1. Identify Candidates: Collect all unique, non-null file paths referenced across all selected schedules
        val candidatePaths =
            schedulesList.flatMap {
                listOfNotNull(it.homeWallpaperPath, it.lockWallpaperPath)
            }.distinct()
        Log.d(TAG, "Candidate paths for batch cleanup: $candidatePaths")

        // 2. Execute DB Deletion: Delete all selected schedule rows in a single batch call (which Room handles natively in a transaction)
        dao.deleteSchedules(schedulesList)
        for (schedule in schedulesList) {
            Log.d("WallpaperDB", "Delete: ID=${schedule.id}")
        }

        // 3. Reference Verification & Unlinking: If the count returns 0, delete the file from the filesystem
        for (path in candidatePaths) {
            val refs = dao.getPathReferenceCount(path)
            Log.d("WallpaperStorage", "Reference check for path $path: count = $refs")
            if (refs == 0) {
                safeDeleteFile(path)
            } else {
                Log.d("WallpaperStorage", "File retained: $path (Reason: Ref count is $refs)")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed batchDeleteSchedulesAndCleanupFiles", e)
        throw e
    }
}

/**
 * Helper function to safely delete a file from disk on the IO dispatcher.
 */
private suspend fun safeDeleteFile(path: String) {
    withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d("WallpaperStorage", "File unlinked: deleted $path (Reason: Ref count is 0)")
                } else {
                    Log.w("WallpaperStorage", "Failed to delete file: $path")
                }
            } else {
                Log.d("WallpaperStorage", "File not found on disk, skipping deletion: $path")
            }
        } catch (e: Exception) {
            Log.e("WallpaperStorage", "Error unlinking file: $path", e)
        }
    }
}
