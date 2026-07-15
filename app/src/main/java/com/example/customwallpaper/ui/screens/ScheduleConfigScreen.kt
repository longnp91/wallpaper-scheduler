package com.example.customwallpaper.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

private const val TAG = "StorageIngestion"

/**
 * A screen that allows configuration of schedule options, including picking
 * images via the Storage Access Framework (SAF) and persisting read permissions.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
fun ScheduleConfigScreen(onImagePicked: (Uri) -> Unit = {}) {
    val context = LocalContext.current

    val pickImageLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) {
                Log.d(TAG, "User canceled the document picker.")
            } else {
                val secureUri = uri
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(secureUri, takeFlags)
                    Log.d(TAG, "Successfully took persistable URI permission for $secureUri")
                    onImagePicked(secureUri)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to take persistable URI permission for $secureUri", e)
                    onImagePicked(secureUri)
                }
            }
        }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = {
            pickImageLauncher.launch(arrayOf("image/*"))
        }) {
            Text(text = "Pick Image")
        }
    }
}
