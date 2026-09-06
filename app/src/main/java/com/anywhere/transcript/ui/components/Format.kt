package com.anywhere.transcript.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b.toDouble() / (1L shl 10))
        else -> "$b B"
    }

    fun duration(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }

    fun stamp(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - ms
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} h ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} d ago"
            else -> android.text.format.DateFormat.getDateFormat(null)?.format(java.util.Date(ms))
                ?: java.text.DateFormat.getDateInstance().format(java.util.Date(ms))
        }
    }
}

fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (t: Throwable) {
    null
}
