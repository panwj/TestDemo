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
 * 帧；Demo 如需对齐竞品扫描结果，应显式使用 COMPETITOR_COMPAT。
 */
data class SimilarScanRequest(
    val forceFull: Boolean = false,
    val imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
    val calculateDuplicateSha256DuringScan: Boolean = false,
    val videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
) {
    internal val normalizedImageFingerprintSize: Int
        get() = imageFingerprintSize.coerceIn(MIN_IMAGE_FINGERPRINT_SIZE, MAX_IMAGE_FINGERPRINT_SIZE)

    companion object {
        const val DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
        const val MIN_IMAGE_FINGERPRINT_SIZE = 96
        const val MAX_IMAGE_FINGERPRINT_SIZE = 512
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
     * 竞品兼容模式：不混用系统视频缩略图，按 0..duration 抽取 7 到 13 个
     * 9x8 视频帧，并使用竞品式“正常至少 2 帧命中、单帧可降到 1 帧”的相似判断。
     *
     * SimilarScanDemo 为了对齐竞品结果会显式使用该模式；其他产品默认仍建议
     * 使用 BALANCED，或按自身速度/准确率目标选择 FAST、ACCURATE。
     */
    COMPETITOR_COMPAT
}
