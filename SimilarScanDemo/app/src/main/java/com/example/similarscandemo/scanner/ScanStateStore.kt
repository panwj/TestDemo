package com.example.similarscandemo.scanner

import android.content.Context

/**
 * 保存 MediaStore 增量扫描游标。
 *
 * generation 值由系统维护，比单纯依赖文件时间更可靠；每隔固定周期仍执行一次
 * 全量扫描，用于同步删除和修复极端情况下遗漏的变更。
 */
class ScanStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun checkpoint(): ScanCheckpoint {
        return ScanCheckpoint(
            imageGeneration = preferences.getLong(KEY_IMAGE_GENERATION, 0L),
            videoGeneration = preferences.getLong(KEY_VIDEO_GENERATION, 0L),
            lastFullScanAt = preferences.getLong(KEY_LAST_FULL_SCAN_AT, 0L),
            mediaStoreVersion = preferences.getString(KEY_MEDIA_STORE_VERSION, "").orEmpty()
        )
    }

    fun shouldRunFullScan(
        currentMediaStoreVersion: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val state = checkpoint()
        return state.mediaStoreVersion != currentMediaStoreVersion ||
            state.lastFullScanAt == 0L ||
            now - state.lastFullScanAt >= FULL_SCAN_INTERVAL_MS
    }

    fun save(
        imageGeneration: Long,
        videoGeneration: Long,
        mediaStoreVersion: String,
        completedFullScan: Boolean
    ) {
        preferences.edit()
            .putLong(KEY_IMAGE_GENERATION, imageGeneration)
            .putLong(KEY_VIDEO_GENERATION, videoGeneration)
            .putString(KEY_MEDIA_STORE_VERSION, mediaStoreVersion)
            .apply {
                if (completedFullScan) {
                    putLong(KEY_LAST_FULL_SCAN_AT, System.currentTimeMillis())
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "media_scan_state"
        private const val KEY_IMAGE_GENERATION = "image_generation"
        private const val KEY_VIDEO_GENERATION = "video_generation"
        private const val KEY_LAST_FULL_SCAN_AT = "last_full_scan_at"
        private const val KEY_MEDIA_STORE_VERSION = "media_store_version"
        private const val FULL_SCAN_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}

data class ScanCheckpoint(
    val imageGeneration: Long,
    val videoGeneration: Long,
    val lastFullScanAt: Long,
    val mediaStoreVersion: String
)
