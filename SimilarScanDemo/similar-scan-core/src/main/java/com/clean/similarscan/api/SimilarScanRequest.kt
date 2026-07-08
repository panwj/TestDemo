package com.clean.similarscan.api

/**
 * SDK 对外扫描请求。
 *
 * scan() 是同步阻塞调用，接入方应放到后台线程、Worker 或前台 Service 中执行。
 *
 * @param forceFull 是否强制全量枚举 MediaStore。
 * @param imageFingerprintSize 图片/截图扫描指纹 Bitmap 的最大边。默认 256，调大可能提升细节
 * 召回但会增加解码、像素遍历和内存成本；调小会提升速度但可能改变识别结果。
 * @param calculateDuplicateSha256DuringScan 是否在扫描主链路中立即补算 Duplicate 候选的
 * SHA-256。默认 false，Duplicate 仍按 duplicateReference 归类，SHA-256 只作为可延后补充的
 * 字节级证据。
 * @param videoFingerprintMode 视频指纹模式。默认 BALANCED，在系统缩略图之外补充少量 MMR
 * 帧；
 * @param enableIntermediateGroupPublish 是否在扫描过程中阶段性发布已发现的 Similar/Duplicate
 * 分组。开启后 SDK 仍先写 candidate edge，再按时间和增量阈值批量物化到 group 表，避免
 * 每扫到一组就刷新。
 * @param firstIntermediateGroupPublishIntervalMs 首次阶段性发布的最小等待时间。默认 60 秒。
 * @param firstIntermediateGroupPublishMinAssets 首次发布至少新增扫描的资源数。默认 5000。
 * @param firstIntermediateGroupPublishMinEdges 首次发布至少新增的候选边数。默认 1。
 * @param intermediateGroupPublishIntervalMs 后续两次阶段性发布的最小时间间隔。默认 90 秒。
 * @param intermediateGroupPublishMinAssets 距离上次发布至少新增扫描的资源数。默认 10000。
 * @param intermediateGroupPublishMinEdges 距离上次发布至少新增的 Similar/Duplicate 候选边数。
 * 默认 20000。资源数和候选边数满足其一即可发布。
 * @param maxIntermediateGroupPublishCount 单次扫描最多阶段性发布几次。默认 2，避免中间
 * rebuild 反复删除和重建 group 导致扫描变慢、首页预览闪烁。实际发布次数还会根据媒体
 * 库规模自适应收敛。
 * @param enableMetricsLog 是否在扫描完成后输出耗时明细日志。默认 true，便于开发和真机
 * 测试阶段观察扫描瓶颈；正式接入时可关闭。
 */
data class SimilarScanRequest(
    val forceFull: Boolean = false,
    val imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
    val calculateDuplicateSha256DuringScan: Boolean = false,
    val videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED,
    val enableIntermediateGroupPublish: Boolean = true,
    val firstIntermediateGroupPublishIntervalMs: Long = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
    val firstIntermediateGroupPublishMinAssets: Int = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS,
    val firstIntermediateGroupPublishMinEdges: Int = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES,
    val intermediateGroupPublishIntervalMs: Long = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
    val intermediateGroupPublishMinAssets: Int = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS,
    val intermediateGroupPublishMinEdges: Int = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES,
    val maxIntermediateGroupPublishCount: Int = DEFAULT_MAX_INTERMEDIATE_GROUP_PUBLISH_COUNT,
    val enableMetricsLog: Boolean = true
) {
    internal val normalizedImageFingerprintSize: Int
        get() = imageFingerprintSize.coerceIn(MIN_IMAGE_FINGERPRINT_SIZE, MAX_IMAGE_FINGERPRINT_SIZE)

    internal val normalizedIntermediateGroupPublishIntervalMs: Long
        get() = intermediateGroupPublishIntervalMs.coerceIn(
            MIN_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
            MAX_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS
        )

    internal val normalizedFirstIntermediateGroupPublishIntervalMs: Long
        get() = firstIntermediateGroupPublishIntervalMs.coerceIn(
            MIN_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
            MAX_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS
        )

    internal val normalizedFirstIntermediateGroupPublishMinAssets: Int
        get() = firstIntermediateGroupPublishMinAssets.coerceAtLeast(0)

    internal val normalizedFirstIntermediateGroupPublishMinEdges: Int
        get() = firstIntermediateGroupPublishMinEdges.coerceAtLeast(0)

    internal val normalizedIntermediateGroupPublishMinAssets: Int
        get() = intermediateGroupPublishMinAssets.coerceAtLeast(0)

    internal val normalizedIntermediateGroupPublishMinEdges: Int
        get() = intermediateGroupPublishMinEdges.coerceAtLeast(0)

    internal val normalizedMaxIntermediateGroupPublishCount: Int
        get() = maxIntermediateGroupPublishCount.coerceAtLeast(0)

    companion object {
        const val DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
        const val MIN_IMAGE_FINGERPRINT_SIZE = 96
        const val MAX_IMAGE_FINGERPRINT_SIZE = 512
        const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 60_000L
        const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS = 5_000
        const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES = 1
        const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 90_000L
        const val MIN_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 5_000L
        const val MAX_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 120_000L
        const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS = 10_000
        const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES = 20_000
        const val DEFAULT_MAX_INTERMEDIATE_GROUP_PUBLISH_COUNT = 2
    }
}

enum class VideoFingerprintMode {
    /**
     * 优先使用系统视频缩略图单帧；只有系统缩略图不可用时才回退 MMR 抽帧。
     */
    FAST,

    /**
     * 系统缩略图 + 少量 MMR 时间点；兼顾速度与避免单帧封面误判。
     */
    BALANCED,

    /**
     * 不把系统缩略图作为唯一依据，使用更多 MMR 时间点进行视频相似识别。
     */
    ACCURATE,

    /**
     * 加强模式：不混用系统视频缩略图，按 0..duration 抽取 7 到 13 个
     * 9x8 视频帧，并使用“正常至少 2 帧命中、单帧可降到 1 帧”的相似判断。
     * 速度会慢但准确度会提高
     */
    REFERENCE_COMPAT,

    /**
     * 自适应均衡模式：先尝试系统缩略图；如果当前设备/媒体库上缩略图连续失败、
     * 成功率过低或耗时过高，本轮扫描会跳过系统缩略图，直接使用 BALANCED 的 4 个
     * MMR 时间点，避免每个视频都重复走慢失败路径。
     */
    ADAPTIVE_BALANCED
}
