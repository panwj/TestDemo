package com.clean.similarscan.internal.scanner

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * 保存 MediaStore 增量扫描游标。
 *
 * generation 值由系统维护，比单纯依赖文件时间更可靠；每隔固定周期仍执行一次
 * 全量扫描，用于同步删除和修复极端情况下遗漏的变更。
 */
class ScanStateStore(context: Context) {
    private val kv: MMKV = MMKVProvider.mmkv(context, PREFS_NAME)

    /**
     * 读取上一次成功保存的扫描游标。
     *
     * 如果上次扫描中断，save() 不会执行，下一次会继续使用旧游标，从而重新覆盖未完成范围。
     */
    fun checkpoint(): ScanCheckpoint {
        return ScanCheckpoint(
            imageGeneration = kv.decodeLong(KEY_IMAGE_GENERATION, 0L),
            videoGeneration = kv.decodeLong(KEY_VIDEO_GENERATION, 0L),
            lastFullScanAt = kv.decodeLong(KEY_LAST_FULL_SCAN_AT, 0L),
            mediaStoreVersion = kv.decodeString(KEY_MEDIA_STORE_VERSION, "").orEmpty()
        )
    }

    /**
     * 判断是否需要退回全量扫描。
     *
     * MediaStore version 变化通常意味着系统媒体库有结构性变化；定期全量扫描用于同步系统外
     * 删除和修复增量游标极端情况下的遗漏。
     */
    fun shouldRunFullScan(
        currentMediaStoreVersion: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val state = checkpoint()
        return state.mediaStoreVersion != currentMediaStoreVersion ||
            state.lastFullScanAt == 0L ||
            now - state.lastFullScanAt >= FULL_SCAN_INTERVAL_MS
    }

    /**
     * 保存扫描游标。
     *
     * 只有完整授权下完成的全量扫描才会刷新 lastFullScanAt；部分授权下不能证明媒体库全集已
     * 对账完成，因此不会更新完整扫描时间。
     */
    fun save(
        imageGeneration: Long,
        videoGeneration: Long,
        mediaStoreVersion: String,
        completedFullScan: Boolean
    ) {
        kv.encode(KEY_IMAGE_GENERATION, imageGeneration)
        kv.encode(KEY_VIDEO_GENERATION, videoGeneration)
        kv.encode(KEY_MEDIA_STORE_VERSION, mediaStoreVersion)
        if (completedFullScan) {
            kv.encode(KEY_LAST_FULL_SCAN_AT, System.currentTimeMillis())
        }
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

/**
 * SDK 内部 MMKV 初始化入口。
 *
 * MMKV 需要在使用前初始化。SDK 作为库不能要求宿主一定在 Application 中提前初始化，
 * 因此在轻量存储首次访问时使用 applicationContext 做幂等初始化。
 */
private object MMKVProvider {
    @Volatile
    private var initialized = false

    fun mmkv(context: Context, id: String): MMKV {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    MMKV.initialize(context.applicationContext)
                    initialized = true
                }
            }
        }
        return MMKV.mmkvWithID(id)
    }
}

/**
 * 上一次扫描完成后保存的 MediaStore 游标快照。
 */
data class ScanCheckpoint(
    val imageGeneration: Long,
    val videoGeneration: Long,
    val lastFullScanAt: Long,
    val mediaStoreVersion: String
)
