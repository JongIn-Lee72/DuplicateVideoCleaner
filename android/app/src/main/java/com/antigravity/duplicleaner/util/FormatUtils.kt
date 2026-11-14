package com.antigravity.duplicleaner.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0

        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatDate(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "-"
        val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
        return sdf.format(Date(epochSeconds * 1000L))
    }
}
