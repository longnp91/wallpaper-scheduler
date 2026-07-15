package com.example.customwallpaper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.example.customwallpaper.receiver.TimeChangeReceiver

class CustomWallpaperApplication : Application() {
    private lateinit var timeChangeReceiver: TimeChangeReceiver

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")

        timeChangeReceiver = TimeChangeReceiver()
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
        registerReceiver(timeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    companion object {
        private const val TAG = "CustomWallpaperApp"
    }
}
