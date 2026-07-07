package com.example.similarscandemo.compress

import android.content.Context
import com.example.similarscandemo.util.MmkvStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Demo 业务层的免费视频压缩次数管理。
 *
 * 竞品规则为每日免费 2 次；这属于产品业务，不放入压缩 SDK。
 */
class VideoCompressionQuotaStore(context: Context) {
    private val kv = MmkvStore.store(context, PREFS_NAME)

    fun remainingFreeCount(): Int {
        resetIfNewDay()
        return (FREE_LIMIT - kv.decodeInt(KEY_USED, 0)).coerceAtLeast(0)
    }

    fun consumeOneFreeQuota() {
        resetIfNewDay()
        val used = kv.decodeInt(KEY_USED, 0)
        kv.encode(KEY_USED, used + 1)
    }

    private fun resetIfNewDay() {
        val today = todayKey()
        if (kv.decodeString(KEY_DAY, null) != today) {
            kv.encode(KEY_DAY, today)
            kv.encode(KEY_USED, 0)
        }
    }

    private fun todayKey(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    companion object {
        const val FREE_LIMIT = 10
        private const val PREFS_NAME = "video_compress_quota"
        private const val KEY_DAY = "day"
        private const val KEY_USED = "used"
    }
}
