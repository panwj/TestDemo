package com.clean.similarscan.internal.similarity

/**
 * 视频指纹计算模式。
 *
 * 模式越靠后通常抽帧越多、耗时越高，但对只在中后段相似的视频召回更稳定。
 */
internal enum class VideoFingerprintMode {
    /** 优先使用系统封面；失败后只抽少量帧，适合快速预览或轻量扫描。 */
    FAST,
    /** 系统封面与少量关键帧结合，是默认的扫描成本和准确率折中方案。 */
    BALANCED,
    /** 抽取更多时间点的帧，提高复杂视频的召回率。 */
    ACCURATE,
    /** 使用固定间隔的 7～13 帧规则，结果更稳定，适合产品级全量扫描。 */
    REFERENCE_COMPAT
}
