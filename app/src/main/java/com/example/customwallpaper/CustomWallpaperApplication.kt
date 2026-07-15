package com.example.customwallpaper

import android.app.Application
import android.util.Log

class CustomWallpaperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
    }

    companion object {
        private const val TAG = "CustomWallpaperApp"
    }
}
