package com.example.customwallpaper.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.customwallpaper.ScheduleViewModel
import com.example.customwallpaper.wallpaperscheduler.data.WallpaperSchedule
import java.io.File

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainSettingsScreen(
    viewModel: ScheduleViewModel,
    onNavigateToSchedule: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var schedules by remember { mutableStateOf<List<WallpaperSchedule>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        schedules = viewModel.scheduleDao.getAllSchedules()
    }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedSchedules = remember { mutableStateListOf<WallpaperSchedule>() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(text = "${selectedSchedules.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedSchedules.clear()
                        }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            viewModel.bulkDeleteSchedules(context, selectedSchedules.toList()) {
                                selectionMode = false
                                selectedSchedules.clear()
                                refreshTrigger++
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            } else {
                TopAppBar(
                    title = { Text(text = "Wallpaper Scheduler") },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = {
                    viewModel.resetDraft()
                    onNavigateToSchedule(null)
                }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Schedule")
                }
            }
        },
    ) { paddingValues ->
        if (schedules.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No schedules defined. Tap [+] to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(schedules, key = { it.id }) { schedule ->
                    val isSelected = selectedSchedules.contains(schedule)
                    ScheduleItem(
                        schedule = schedule,
                        isSelected = isSelected,
                        selectionMode = selectionMode,
                        onItemClick = {
                            if (selectionMode) {
                                if (isSelected) {
                                    selectedSchedules.remove(schedule)
                                    if (selectedSchedules.isEmpty()) {
                                        selectionMode = false
                                    }
                                } else {
                                    selectedSchedules.add(schedule)
                                }
                            } else {
                                viewModel.loadSchedule(schedule)
                                onNavigateToSchedule(schedule.id)
                            }
                        },
                        onItemLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedSchedules.add(schedule)
                            }
                        },
                        onToggleActive = { active ->
                            viewModel.toggleScheduleActive(context, schedule, active)
                            refreshTrigger++
                        },
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScheduleItem(
    schedule: WallpaperSchedule,
    isSelected: Boolean,
    selectionMode: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onItemClick,
                    onLongClick = onItemLongClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onItemClick() },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "From ${formatTime(schedule.fromTimeMin)} To ${formatTime(schedule.toTimeMin)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = schedule.weekdays,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val targetText =
                    listOfNotNull(
                        if (schedule.homeWallpaperPath != null) "Home" else null,
                        if (schedule.lockWallpaperPath != null) "Lock" else null,
                    ).joinToString(" & ")
                Text(
                    text = "Target: $targetText (Priority: ${schedule.priority})",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    schedule.homeWallpaperPath?.let { path ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Home preview",
                                modifier =
                                    Modifier
                                        .size(50.dp, 80.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Text(text = "Home", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    schedule.lockWallpaperPath?.let { path ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Lock preview",
                                modifier =
                                    Modifier
                                        .size(50.dp, 80.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Text(text = "Lock", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Switch(
                checked = schedule.isActive,
                onCheckedChange = onToggleActive,
            )
        }
    }
}

private fun formatTime(minutes: Int): String {
    val hour = minutes / 60
    val min = minutes % 60
    val ampm = if (hour >= 12) "PM" else "AM"
    val displayHour =
        when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
    return String.format("%02d:%02d %s", displayHour, min, ampm)
}
