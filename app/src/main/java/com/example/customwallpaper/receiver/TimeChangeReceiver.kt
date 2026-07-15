package com.example.customwallpaper.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.customwallpaper.worker.WallpaperSchedulerHelper

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        Log.d(TAG, "onReceive called with action: $action")
        if (action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED
        ) {
            WallpaperSchedulerHelper.triggerImmediateEvaluation(context)
        }
    }

    companion object {
        private const val TAG = "TimeChangeReceiver"
    }
}
