package com.example.customwallpaper.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "onReceive called with action: ${intent.action}")
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
