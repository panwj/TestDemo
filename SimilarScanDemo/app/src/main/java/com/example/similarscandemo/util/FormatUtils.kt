package com.example.similarscandemo.util

import java.util.Locale

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
