package com.anywhere.transcript.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class FormatTest {
    private val now = 1_789_920_000_000L

    @Test
    fun `recent timestamps retain relative labels`() {
        for ((age, expected) in listOf(
            0L to "just now",
            59_999L to "just now",
            60_000L to "1 min ago",
            3_599_999L to "59 min ago",
            3_600_000L to "1 h ago",
            86_399_999L to "23 h ago",
            86_400_000L to "1 d ago",
            (TimeUnit.DAYS.toMillis(7) - 1) to "6 d ago",
        )) {
            assertEquals(expected, Format.stamp(now - age, now))
        }
    }

    @Test
    fun `week-old and older timestamps format without an Android context`() {
        val originalLocale = Locale.getDefault()
        val originalZone = TimeZone.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.UK, Locale.GERMANY)) {
                Locale.setDefault(locale)
                for (zone in listOf("UTC", "Asia/Kolkata", "America/Los_Angeles")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    for (days in listOf(7L, 8L, 365L)) {
                        val timestamp = now - TimeUnit.DAYS.toMillis(days)
                        assertEquals(
                            DateFormat.getDateInstance().format(Date(timestamp)),
                            Format.stamp(timestamp, now),
                        )
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }
}
