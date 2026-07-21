package com.example.customwallpaper.util

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TimeFormatterTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Locale.setDefault(Locale.US)
        context.resources.configuration.setLocale(Locale.US)
    }

    @Test
    fun testFormatTime_12HourConfig() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")

        // 0 minutes (12:00 AM)
        val time0 = TimeFormatter.formatTime(context, 0)
        val clean0 = time0.replace('\u202f', ' ').replace('\u00a0', ' ').trim().uppercase(Locale.US)
        assertTrue("Expected 12:00 AM, but got: $clean0", clean0.contains("12:00") && clean0.contains("AM"))

        // 720 minutes (12:00 PM)
        val time720 = TimeFormatter.formatTime(context, 720)
        val clean720 = time720.replace('\u202f', ' ').replace('\u00a0', ' ').trim().uppercase(Locale.US)
        assertTrue("Expected 12:00 PM, but got: $clean720", clean720.contains("12:00") && clean720.contains("PM"))

        // 1439 minutes (11:59 PM)
        val time1439 = TimeFormatter.formatTime(context, 1439)
        val clean1439 = time1439.replace('\u202f', ' ').replace('\u00a0', ' ').trim().uppercase(Locale.US)
        assertTrue("Expected 11:59 PM, but got: $clean1439", clean1439.contains("11:59") && clean1439.contains("PM"))
    }

    @Test
    fun testFormatTime_24HourConfig() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")

        // 0 minutes (00:00)
        val time0 = TimeFormatter.formatTime(context, 0)
        val clean0 = time0.replace('\u202f', ' ').replace('\u00a0', ' ').trim()
        assertTrue("Expected 00:00 or 0:00, but got: $clean0", clean0 == "00:00" || clean0 == "0:00")

        // 720 minutes (12:00)
        val time720 = TimeFormatter.formatTime(context, 720)
        val clean720 = time720.replace('\u202f', ' ').replace('\u00a0', ' ').trim()
        assertEquals("12:00", clean720)

        // 1439 minutes (23:59)
        val time1439 = TimeFormatter.formatTime(context, 1439)
        val clean1439 = time1439.replace('\u202f', ' ').replace('\u00a0', ' ').trim()
        assertEquals("23:59", clean1439)
    }
}
