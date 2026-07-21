package com.example.customwallpaper

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.customwallpaper.ui.screens.MainSettingsScreen
import com.example.customwallpaper.ui.screens.ScheduleConfigScreen
import com.example.customwallpaper.ui.screens.WallpaperCropEditorScreen
import com.example.customwallpaper.wallpaperscheduler.data.ScheduleDao
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperDatabase
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen {
    object MainSettings : Screen()

    data class ScheduleConfig(val scheduleId: Long?) : Screen()

    data class CropEditor(
        val imageUri: Uri,
        val targetSlot: String,
        val scheduleId: Long?,
    ) : Screen()
}

class ScheduleViewModel(val scheduleDao: ScheduleDao) : ViewModel() {
    val weekdaysState = mutableStateOf("")
    val fromTimeMinState = mutableStateOf(0)
    val toTimeMinState = mutableStateOf(0)

    val homeWallpaperPathState = mutableStateOf<String?>(null)
    val lockWallpaperPathState = mutableStateOf<String?>(null)
    val isActiveState = mutableStateOf(true)
    val editingScheduleId = mutableStateOf<Long?>(null)

    val sessionTempFiles = mutableListOf<String>()

    fun cleanupSessionTempFiles(excludeSaved: Boolean = false) {
        val iterator = sessionTempFiles.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            val isSaved = excludeSaved && (path == homeWallpaperPathState.value || path == lockWallpaperPathState.value)
            if (!isSaved) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ScheduleViewModel", "Failed to delete temp file: $path", e)
                }
                iterator.remove()
            }
        }
    }

    fun resetDraft() {
        weekdaysState.value = ""
        fromTimeMinState.value = 0
        toTimeMinState.value = 0
        homeWallpaperPathState.value = null
        lockWallpaperPathState.value = null
        isActiveState.value = true
        editingScheduleId.value = null
        cleanupSessionTempFiles(excludeSaved = false)
    }

    fun loadSchedule(schedule: WallpaperSchedule) {
        weekdaysState.value = schedule.weekdays
        fromTimeMinState.value = schedule.fromTimeMin
        toTimeMinState.value = schedule.toTimeMin
        homeWallpaperPathState.value = schedule.homeWallpaperPath
        lockWallpaperPathState.value = schedule.lockWallpaperPath
        isActiveState.value = schedule.isActive
        editingScheduleId.value = schedule.id
        sessionTempFiles.clear()
    }

    fun toggleScheduleActive(
        context: android.content.Context,
        schedule: WallpaperSchedule,
        isActive: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = schedule.copy(isActive = isActive)
            scheduleDao.updateSchedule(updated)
            com.example.customwallpaper.worker.WallpaperSchedulerHelper.triggerImmediateEvaluation(context)
        }
    }

    fun bulkDeleteSchedules(
        context: android.content.Context,
        schedules: List<WallpaperSchedule>,
        onComplete: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.example.customwallpaper.wallpaperscheduler.data.batchDeleteSchedulesAndCleanupFiles(
                    context = context,
                    dao = scheduleDao,
                    schedulesList = schedules,
                )
                com.example.customwallpaper.worker.WallpaperSchedulerHelper.triggerImmediateEvaluation(context)
            } catch (e: Exception) {
                android.util.Log.e("ScheduleViewModel", "Failed to bulk delete schedules", e)
            } finally {
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    suspend fun saveSchedule(context: android.content.Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val newSchedule =
                    WallpaperSchedule(
                        id = editingScheduleId.value ?: 0L,
                        weekdays = weekdaysState.value,
                        fromTimeMin = fromTimeMinState.value,
                        toTimeMin = toTimeMinState.value,
                        homeWallpaperPath = homeWallpaperPathState.value,
                        lockWallpaperPath = lockWallpaperPathState.value,
                        isActive = isActiveState.value,
                    )

                if (editingScheduleId.value == null) {
                    // Create new
                    scheduleDao.insertSchedule(newSchedule)
                } else {
                    // Update
                    val oldSchedule = scheduleDao.getAllSchedules().find { it.id == editingScheduleId.value }
                    if (oldSchedule != null) {
                        com.example.customwallpaper.wallpaperscheduler.data.updateScheduleAndCleanupFiles(
                            context = context,
                            dao = scheduleDao,
                            oldSchedule = oldSchedule,
                            newSchedule = newSchedule,
                        )
                    } else {
                        scheduleDao.updateSchedule(newSchedule)
                    }
                }

                com.example.customwallpaper.worker.WallpaperSchedulerHelper.triggerImmediateEvaluation(context)

                true
            } catch (e: Exception) {
                android.util.Log.e("ScheduleViewModel", "Failed to save schedule", e)
                false
            }
        }
    }
}

class ScheduleViewModelFactory(private val scheduleDao: ScheduleDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScheduleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScheduleViewModel(scheduleDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = WallpaperDatabase.getInstance(applicationContext)
        val factory = ScheduleViewModelFactory(database.scheduleDao())
        val viewModel = ViewModelProvider(this, factory)[ScheduleViewModel::class.java]

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.MainSettings) }

                when (val screen = currentScreen) {
                    is Screen.MainSettings -> {
                        MainSettingsScreen(
                            viewModel = viewModel,
                            onNavigateToSchedule = { scheduleId ->
                                currentScreen = Screen.ScheduleConfig(scheduleId)
                            },
                        )
                    }
                    is Screen.ScheduleConfig -> {
                        ScheduleConfigScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                currentScreen = Screen.MainSettings
                            },
                            onNavigateToCropEditor = { uri, slot ->
                                currentScreen = Screen.CropEditor(uri, slot, screen.scheduleId)
                            },
                        )
                    }
                    is Screen.CropEditor -> {
                        WallpaperCropEditorScreen(
                            viewModel = viewModel,
                            imageUri = screen.imageUri,
                            targetSlot = screen.targetSlot,
                            onNavigateBack = {
                                currentScreen = Screen.ScheduleConfig(screen.scheduleId)
                            },
                        )
                    }
                }
            }
        }
    }
}
