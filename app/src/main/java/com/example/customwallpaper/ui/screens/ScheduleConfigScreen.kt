package com.example.customwallpaper.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.customwallpaper.ScheduleViewModel
import kotlinx.coroutines.launch
import java.io.File

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleConfigScreen(
    viewModel: ScheduleViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCropEditor: (Uri, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allWeekdays = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    val selectedDays =
        remember(viewModel.weekdaysState.value) {
            viewModel.weekdaysState.value.split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

    fun toggleDay(day: String) {
        val updated =
            if (selectedDays.contains(day)) {
                selectedDays - day
            } else {
                selectedDays + day
            }
        viewModel.weekdaysState.value = allWeekdays.filter { updated.contains(it) }.joinToString(",")
    }

    var activeTarget by remember { mutableStateOf<String?>(null) }

    val pickImageLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: SecurityException) {
                    android.util.Log.e("ScheduleConfigScreen", "Failed to take persistable URI permission", e)
                }
                activeTarget?.let { slot ->
                    onNavigateToCropEditor(uri, slot)
                }
            }
        }

    fun launchPickerForTarget(slot: String) {
        activeTarget = slot
        pickImageLauncher.launch(arrayOf("image/*"))
    }

    val isValid =
        viewModel.weekdaysState.value.isNotEmpty() &&
            viewModel.priorityState.value.isNotEmpty() &&
            (viewModel.homeWallpaperPathState.value != null || viewModel.lockWallpaperPathState.value != null)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            if (viewModel.editingScheduleId.value == null) {
                                "Create Schedule"
                            } else {
                                "Edit Schedule"
                            },
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Weekdays selection
            Text(text = "Active Days", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                allWeekdays.forEachIndexed { index, day ->
                    val isSelected = selectedDays.contains(day)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { toggleDay(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = dayLabels[index],
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Time range selection
            Text(text = "Active Time Window", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        showTimePicker(context, viewModel.fromTimeMinState.value) { min ->
                            viewModel.fromTimeMinState.value = min
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Start: ${formatTime(viewModel.fromTimeMinState.value)}")
                }

                OutlinedButton(
                    onClick = {
                        showTimePicker(context, viewModel.toTimeMinState.value) { min ->
                            viewModel.toTimeMinState.value = min
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "End: ${formatTime(viewModel.toTimeMinState.value)}")
                }
            }

            // Priority Input
            OutlinedTextField(
                value = viewModel.priorityState.value,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() }) {
                        viewModel.priorityState.value = newValue
                    }
                },
                label = { Text("Priority (Higher Wins)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Dual Previews
            Text(text = "Wallpapers", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Home slot preview
                WallpaperSlotPreview(
                    title = "Home Screen",
                    path = viewModel.homeWallpaperPathState.value,
                    onTap = { launchPickerForTarget("home") },
                    modifier = Modifier.weight(1f),
                )

                // Lock slot preview
                WallpaperSlotPreview(
                    title = "Lock Screen",
                    path = viewModel.lockWallpaperPathState.value,
                    onTap = { launchPickerForTarget("lock") },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.cleanupSessionTempFiles(excludeSaved = false)
                        onNavigateBack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Cancel")
                }

                Button(
                    onClick = {
                        scope.launch {
                            val success = viewModel.saveSchedule(context)
                            if (success) {
                                onNavigateBack()
                            } else {
                                Toast.makeText(context, "Error saving schedule", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Save")
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun WallpaperSlotPreview(
    title: String,
    path: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) {
                AsyncImage(
                    model = File(path),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Pick Image",
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to choose",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun showTimePicker(
    context: Context,
    initialMin: Int,
    onTimeSelected: (Int) -> Unit,
) {
    val currentHour = initialMin / 60
    val currentMinute = initialMin % 60
    android.app.TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            onTimeSelected(hourOfDay * 60 + minute)
        },
        currentHour,
        currentMinute,
        false,
    ).show()
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
