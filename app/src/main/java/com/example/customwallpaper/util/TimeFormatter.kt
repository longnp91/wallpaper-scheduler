package com.example.customwallpaper.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar

object TimeFormatter {
    fun formatTime(
        context: Context,
        minutes: Int,
    ): String {
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
            }
        val timeFormat = DateFormat.getTimeFormat(context)
        return timeFormat.format(calendar.time)
    }
}
