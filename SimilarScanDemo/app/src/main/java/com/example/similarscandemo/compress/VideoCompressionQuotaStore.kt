package com.example.similarscandemo.compress

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Demo 业务层的免费视频压缩次数管理。
 *
 * 竞品规则为每日免费 2 次；这属于产品业务，不放入压缩 SDK。
 */
class VideoCompressionQuotaStore(context: Context) {
    private val prefs = context.getSharedPreferences("video_compress_quota", Context.MODE_PRIVATE)

    fun remainingFreeCount(): Int {
        resetIfNewDay()
        return (FREE_LIMIT - prefs.getInt(KEY_USED, 0)).coerceAtLeast(0)
    }

    fun consumeOneFreeQuota() {
        resetIfNewDay()
        val used = prefs.getInt(KEY_USED, 0)
        prefs.edit().putInt(KEY_USED, used + 1).apply()
    }

    private fun resetIfNewDay() {
        val today = todayKey()
        if (prefs.getString(KEY_DAY, null) != today) {
            prefs.edit()
                .putString(KEY_DAY, today)
                .putInt(KEY_USED, 0)
                .apply()
        }
    }

    private fun todayKey(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    companion object {
        const val FREE_LIMIT = 10
        private const val KEY_DAY = "day"
        private const val KEY_USED = "used"
    }
}
